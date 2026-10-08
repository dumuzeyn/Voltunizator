package com.dumuzeyn.mp3player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CoverAppearanceInstrumentedTest {
    private Instrumentation instrumentation;
    private MainActivityCore host;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = ApplicationProvider.getApplicationContext();
        host = InstrumentedTestSupport.launchForPlayback(instrumentation, context);
    }

    @After
    public void tearDown() {
        if (host != null) InstrumentedTestSupport.finishActivity(instrumentation, host);
    }

    @Test
    public void selectedShapeClipsFallbackBackground() {
        instrumentation.runOnMainSync(() -> {
            ShapedCoverImageView cover = new ShapedCoverImageView(host);
            cover.setBackgroundColor(Color.RED);
            int size = 96;
            int spec = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY);
            cover.measure(spec, spec);
            cover.layout(0, 0, size, size);
            for (String shape : new String[]{"circle", "triangle", "star"}) {
                host.appearanceState.coverShape = shape;
                cover.invalidateCoverShape();
                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                cover.draw(new Canvas(bitmap));
                assertEquals(shape + " leaked a square corner", 0,
                        Color.alpha(bitmap.getPixel(0, 0)));
                assertEquals(shape + " lost its fallback fill", Color.RED,
                        bitmap.getPixel(size / 2, size / 2));
                bitmap.recycle();
            }
        });
    }

    @Test
    public void cachedCoverReadsCurrentSpeedBeforeSeeking() {
        instrumentation.runOnMainSync(() -> {
            host.appearanceState.rotateCovers = true;
            RotatingCoverImageView cover = new RotatingCoverImageView(host);
            host.appearanceState.fullPlayerRotationSpeed = 200;
            cover.updatePlaybackState(null, false);
            cover.beginSeekSpin(0);
            cover.updateSeekSpin(4500);
            assertEquals(180f, cover.getRotation(), 0.01f);
            cover.endSeekSpin(0, false);

            host.appearanceState.fullPlayerRotationSpeed = 50;
            cover.updatePlaybackState(null, false);
            cover.beginSeekSpin(0);
            cover.updateSeekSpin(9000);
            assertEquals(90f, cover.getRotation(), 0.01f);
        });
    }

    @Test
    public void rotatingShapesKeepTheirScaleAndNeverDrawOutsideTheCoverSlot() {
        instrumentation.runOnMainSync(() -> {
            host.appearanceState.rotateCovers = true;
            host.appearanceState.fullPlayerRotationSpeed = 100;
            for (String shape : new String[]{"circle", "triangle", "hexagon", "star", "diamond", "rounded"}) {
                host.appearanceState.coverShape = shape;
                RotatingCoverImageView cover = new RotatingCoverImageView(host);
                cover.setBackgroundColor(Color.RED);
                int spec = View.MeasureSpec.makeMeasureSpec(128, View.MeasureSpec.EXACTLY);
                cover.measure(spec, spec);
                cover.layout(0, 0, 128, 128);
                cover.beginSeekSpin(0);
                float scale = cover.getScaleX();
                int initialArea = 0;
                for (int degree = 0; degree < 360; degree += 15) {
                    cover.updateSeekSpin(degree * 50);
                    assertEquals(shape + " changed size", scale, cover.getScaleX(), 0.0001f);
                    Bitmap bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    canvas.translate(80, 80);
                    canvas.rotate(cover.getRotation());
                    canvas.scale(cover.getScaleX(), cover.getScaleY());
                    canvas.translate(-64, -64);
                    cover.draw(canvas);
                    int area = 0;
                    for (int y = 0; y < 160; y++) for (int x = 0; x < 160; x++) {
                        if (Color.alpha(bitmap.getPixel(x, y)) == 0) continue;
                        area++;
                        assertTrue(shape + " escaped its slot", x >= 15 && x <= 144 && y >= 15 && y <= 144);
                    }
                    if (degree == 0) initialArea = area;
                    assertTrue(shape + " changed visible area", Math.abs(area - initialArea) < initialArea * 0.05);
                    bitmap.recycle();
                }
                cover.endSeekSpin(0, false);
            }
        });
    }
}
