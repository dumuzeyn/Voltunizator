#include <jni.h>
#include <algorithm>
#include <memory>
#include <stdexcept>
#include <omp.h>
#include "model.hpp"
#include "tiled_attention.hpp"
#include "conv.hpp"

namespace {
struct Cancelled {};
void fail(JNIEnv* env, const char* type, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass(type), message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_dumuzeyn_mp3player_DemucsSeparator_create(JNIEnv* env, jobject, jstring path) {
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (!chars) return 0;
    std::string filename(chars);
    env->ReleaseStringUTFChars(path, chars);
    try {
        omp_set_dynamic(0);
        omp_set_num_threads(std::max(2, std::min(4, omp_get_num_procs())));
        // Parallelize independent tiles, not every small GEMM inside each tile.
        Eigen::setNbThreads(1);
        auto model = std::make_unique<demucscpp::demucs_model>();
        if (!demucscpp::load_demucs_model(filename, model.get()) || !model->is_4sources)
            throw std::runtime_error("Invalid Demucs model");
        return reinterpret_cast<jlong>(model.release());
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "Not enough memory for Demucs");
    } catch (const std::exception& error) {
        fail(env, "java/lang/IllegalStateException", error.what());
    }
    return 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_dumuzeyn_mp3player_DemucsSeparator_separate(JNIEnv* env, jobject, jlong handle,
                                                   jfloatArray input, jobject callback) {
    try {
        const int size = env->GetArrayLength(input);
        if (!handle || size < 4 || size % 2 || size > 44100 * 2 * 23)
            throw std::invalid_argument("Invalid stereo window");
        Eigen::MatrixXf audio(2, size / 2);
        env->GetFloatArrayRegion(input, 0, size, audio.data());
        if (env->ExceptionCheck()) return nullptr;
        if (!audio.allFinite()) throw std::invalid_argument("Non-finite PCM");
        auto method = env->GetMethodID(env->GetObjectClass(callback), "update", "(F)Z");
        if (!method) return nullptr;
        auto progress = [&](float value, const std::string&) {
            if (!env->CallBooleanMethod(callback, method, value) || env->ExceptionCheck()) throw Cancelled{};
        };
        progress(0.f, "");
        Eigen::Tensor3dXf output(4, 2, size / 2);
        const Eigen::VectorXf mono = audio.colwise().mean();
        const float variance = (mono.array() - mono.mean()).square().mean();
        if (variance < 1e-12f) {
            // The upstream normalization divides by mono standard deviation.
            // Preserve silent/DC/anti-phase material in "other" instead of NaNs.
            output.setZero();
            for (int frame = 0; frame < size / 2; ++frame)
                for (int channel = 0; channel < 2; ++channel) output(2, channel, frame) = audio(channel, frame);
        } else {
            output = demucscpp::demucs_inference(*reinterpret_cast<demucscpp::demucs_model*>(handle), audio, progress);
        }
        auto result = env->NewObjectArray(4, env->FindClass("[F"), nullptr);
        if (!result) return nullptr;
        std::vector<float> channelOutput(size);
        for (int stem = 0; stem < 4; ++stem) {
            progress(1.f, "");
            for (int frame = 0; frame < size / 2; ++frame) for (int channel = 0; channel < 2; ++channel) {
                const float sample = output(stem, channel, frame);
                if (!std::isfinite(sample)) throw std::runtime_error("Non-finite model output");
                channelOutput[frame * 2 + channel] = sample;
            }
            auto samples = env->NewFloatArray(size);
            if (!samples) return nullptr;
            env->SetFloatArrayRegion(samples, 0, size, channelOutput.data());
            env->SetObjectArrayElement(result, stem, samples);
            env->DeleteLocalRef(samples);
            if (env->ExceptionCheck()) return nullptr;
        }
        return result;
    } catch (const Cancelled&) {
        fail(env, "java/util/concurrent/CancellationException", "Separation cancelled");
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "Not enough memory for separation");
    } catch (const std::exception& error) {
        fail(env, "java/lang/IllegalStateException", error.what());
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dumuzeyn_mp3player_DemucsSeparator_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<demucscpp::demucs_model*>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dumuzeyn_mp3player_DemucsSeparator_verifyAttention(JNIEnv*, jobject) {
    const Eigen::MatrixXf q = Eigen::MatrixXf::Random(259, 32);
    const Eigen::MatrixXf k = Eigen::MatrixXf::Random(301, 32);
    const Eigen::MatrixXf v = Eigen::MatrixXf::Random(301, 32);
    const auto tiled = voltune_attention(q, k, v, 4);
    Eigen::MatrixXf reference(259, 32);
    for (int h = 0; h < 4; ++h) {
        Eigen::MatrixXf scores = q.middleCols(h * 8, 8) * k.middleCols(h * 8, 8).transpose() / std::sqrt(8.f);
        const Eigen::VectorXf maximum = scores.rowwise().maxCoeff();
        scores = (scores - maximum.replicate(1, 301)).array().exp();
        const Eigen::VectorXf sums = scores.rowwise().sum();
        scores = (scores.array() / sums.replicate(1, 301).array()).matrix();
        reference.middleCols(h * 8, 8) = scores * v.middleCols(h * 8, 8);
    }
    if ((tiled - reference).cwiseAbs().maxCoeff() >= 1e-5f) return false;
    Eigen::Tensor3dXf input(3, 19, 127);
    input.setRandom();
    const Eigen::MatrixXf weights = Eigen::MatrixXf::Random(5, 3 * 3 * 2);
    const Eigen::MatrixXf dense = demucscpp::im2col<3, 2, 2, 1, 1, 0, 1, 1>(input) * weights.transpose();
    const auto tiledConv = voltune_conv_product<3, 2, 2, 1, 1, 0, 1, 1, false>(input, weights);
    if ((tiledConv - dense).cwiseAbs().maxCoeff() >= 1e-5f) return false;
    const Eigen::MatrixXf denseTr = demucscpp::im2col_transposed<3, 2, 2, 3, 1, 1, 2, 1>(input) * weights.transpose();
    const auto tiledTr = voltune_conv_product<3, 2, 2, 3, 1, 1, 2, 1, true>(input, weights);
    return (tiledTr - denseTr).cwiseAbs().maxCoeff() < 1e-5f;
}
