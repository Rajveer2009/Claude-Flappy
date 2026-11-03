package com.claudegame.flappy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.view.DisplayCutout;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.os.SystemClock;
import android.view.MotionEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/** Plays the game headlessly and renders a few frames to PNG for a visual smoke test. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class GameViewRenderTest {

    private static final int W = 720, H = 1600;

    @Test
    public void playsAndRenders() throws Exception {
        GameView v = newView();
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        String out = renderDir();

        step(v, c, 30);
        save(bmp, out + "/1_title.png");

        tap(v, W * 0.5f); // start (and first flap)
        assertTrue(v.isRunning());
        // an autopilot that flaps whenever the spark sinks below the next gap
        int frames = 0;
        while (frames++ < 900 && v.score() < 3 && !v.isOver()) autopilot(v, c);
        assertTrue("passes pipes, score " + v.score(), v.score() >= 3);
        save(bmp, out + "/2_flying.png");
        tap(v, W * 0.5f);
        step(v, c, 4);
        save(bmp, out + "/3_flap.png");

        // stop flapping: the spark falls and crashes
        frames = 0;
        while (frames++ < 600 && !v.isOver()) step(v, c, 1);
        assertTrue("crashes without flapping", v.isOver());
        step(v, c, 90);
        save(bmp, out + "/4_game_over.png");

        // tap to try again
        tap(v, W * 0.5f);
        assertTrue("restarts", v.isRunning());
        assertTrue(v.score() == 0);
    }

    @Test
    public void flapGoesUpGravityPullsDown() throws Exception {
        GameView v = newView();
        Canvas c = new Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
        step(v, c, 3);
        tap(v, W * 0.5f);
        assertTrue("flap sends the spark up", v.birdVy() < 0);
        float y0 = v.birdY();
        step(v, c, 4);
        assertTrue("rises after a flap", v.birdY() < y0);
        step(v, c, 40);
        assertTrue("falls without flapping", v.birdVy() > 0);
    }

    @Test
    public void pausesAndResumes() throws Exception {
        GameView v = newView();
        Canvas c = new Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
        step(v, c, 3);
        tap(v, W * 0.5f);
        step(v, c, 5);
        v.onKeyDown(KeyEvent.KEYCODE_P, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P));
        assertFalse(v.isRunning());
        float y = v.birdY();
        step(v, c, 30);
        assertTrue("frozen while paused", y == v.birdY());
        tap(v, W * 0.5f);
        assertTrue("tap resumes", v.isRunning());
        // the app going to the background pauses too
        v.onHostPause();
        assertFalse(v.isRunning());
    }

    /** With the phone in dark mode the game should start (and stay) dark. */
    @Test
    @Config(qualifiers = "night")
    public void followsSystemDarkMode() throws Exception {
        GameView v = newView();
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        String out = renderDir();

        step(v, c, 30);
        save(bmp, out + "/5_dark_title.png");
        assertTrue("background should be dark", backgroundLuminance(bmp) < 0.2f);

        tap(v, W * 0.5f);
        int frames = 0;
        while (frames++ < 900 && v.score() < 2 && !v.isOver()) autopilot(v, c);
        save(bmp, out + "/6_dark_flying.png");
        assertTrue("stays dark while playing", backgroundLuminance(bmp) < 0.2f);

        // Switching the phone back to light mode mid-game fades the palette back to light.
        android.content.res.Configuration light =
                new android.content.res.Configuration(v.getResources().getConfiguration());
        light.uiMode = (light.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_NO;
        v.dispatchConfigurationChanged(light);
        v.onKeyDown(KeyEvent.KEYCODE_P, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P));
        step(v, c, 120);
        assertTrue("turns light again", backgroundLuminance(bmp) > 0.6f);
    }

    @Test
    public void playsSoundEffects() throws Exception {
        GameView v = newView();
        Canvas c = new Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
        org.robolectric.shadows.ShadowSoundPool pool = shadowOf(v.sfx().pool);

        step(v, c, 5);
        tap(v, W * 0.5f); // start
        assertTrue("start sound", pool.wasResourcePlayed(R.raw.sfx_start));
        assertTrue("flap sound", pool.wasResourcePlayed(R.raw.sfx_flap));
        int frames = 0;
        while (frames++ < 900 && v.score() < 1 && !v.isOver()) autopilot(v, c);
        assertTrue("score sound", pool.wasResourcePlayed(R.raw.sfx_score));
        for (int i = 0; i < 600 && !v.isOver(); i++) step(v, c, 1);
        assertTrue("crash sound", pool.wasResourcePlayed(R.raw.sfx_crash));

        // 'M' mutes (also available as an on-screen button on menus); nothing plays after that
        v.onKeyDown(KeyEvent.KEYCODE_M, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_M));
        assertTrue(v.sfx().isMuted());
        pool.clearPlayed();
        step(v, c, 60);
        tap(v, W * 0.5f); // restart
        assertFalse("muted", pool.wasResourcePlayed(R.raw.sfx_start));
    }

    @Test
    public void playsBackgroundMusic() throws Exception {
        GameView v = newView();
        Music m = v.music();
        v.onHostResume(); // what the activity does when it comes to the foreground
        assertTrue("music starts", waitFor(() -> m.playing && m.framesWritten > Synth.SR, 20000));

        // N toggles music off: it fades out and the track stops
        v.onKeyDown(KeyEvent.KEYCODE_N, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N));
        assertFalse(m.isEnabled());
        assertTrue("music stops", waitFor(() -> !m.playing, 5000));
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        step(v, new Canvas(bmp), 3);
        String out = System.getProperty("renderDir", "build/renders");
        new File(out).mkdirs();
        save(bmp, out + "/8_music_off.png");

        // back on, then the app goes to the background: stops again
        v.onKeyDown(KeyEvent.KEYCODE_N, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_N));
        assertTrue("music resumes", waitFor(() -> m.playing, 5000));
        v.onHostPause();
        assertTrue("paused in background", waitFor(() -> !m.playing, 5000));
        v.release();
    }

    @Test
    public void musicLoopIsSeamless() throws Exception {
        float[] loop = Synth.musicLoop();
        double bar = 60.0 / Synth.BPM * 4;
        assertTrue(Math.abs(loop.length - bar * Synth.BARS * Synth.SR) < 2);
        float peak = 0;
        for (float x : loop) peak = Math.max(peak, Math.abs(x));
        // the jump from the last sample back to the first is no bigger than a normal step
        float maxStep = 0;
        for (int i = 1; i < loop.length; i++) maxStep = Math.max(maxStep, Math.abs(loop[i] - loop[i - 1]));
        float seam = Math.abs(loop[0] - loop[loop.length - 1]);
        assertTrue("seam " + seam + " vs max step " + maxStep, seam <= maxStep);
        String out = System.getProperty("renderDir", "build/renders");
        new File(out).mkdirs();
        writeWav(new File(out, "music_loop.wav"), Synth.toPcm(loop, 0.9f));
    }

    /** Saves 16-bit mono PCM as a WAV so the loop can be listened to / inspected. */
    private static void writeWav(File file, short[] pcm) throws Exception {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + pcm.length * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + pcm.length * 2).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(Synth.SR).putInt(Synth.SR * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(pcm.length * 2);
        for (short v : pcm) b.putShort(v);
        try (FileOutputStream fo = new FileOutputStream(file)) {
            fo.write(b.array());
        }
    }

    private interface Cond {
        boolean ok();
    }

    private static boolean waitFor(Cond c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return true;
            Thread.sleep(20);
        }
        return c.ok();
    }

    /** A phone with a punch-hole camera at the top (portrait): HUD must avoid it. */
    @Test
    public void hudAvoidsCameraCutout() throws Exception {
        GameView v = newView();
        int notch = 130;
        WindowInsets insets = new WindowInsets.Builder()
                .setDisplayCutout(new DisplayCutout(Insets.of(0, notch, 0, 0), null, null, null, null))
                .build();
        v.dispatchApplyWindowInsets(insets);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        step(v, c, 5);
        tap(v, W * 0.5f);
        step(v, c, 10);
        v.onKeyDown(KeyEvent.KEYCODE_P, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P));
        step(v, c, 1);
        v.onKeyDown(KeyEvent.KEYCODE_P, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_P));
        step(v, c, 1);

        // no HUD (score, pause button, best) inside the cutout strip, away from pillars
        int bg = bmp.getPixel(W / 2, notch + 400);
        for (int x = 0; x < W; x += 4) {
            for (int y = 0; y < notch; y += 4) {
                if (x > W * 0.6f && x < W * 0.95f && y > 20 && y < 110) continue; // sun
                assertTrue("HUD drawn under notch at " + x + "," + y,
                        Math.abs(luminance(bmp.getPixel(x, y)) - luminance(bg)) < 0.12f);
            }
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        c.drawCircle(W / 2f, notch / 2f, 30, p);
        save(bmp, renderDir() + "/7_cutout.png");
    }

    private static void autopilot(GameView v, Canvas c) throws Exception {
        // A flap always lifts the spark ~12u, so flap once it sinks ~4.5u below the gap
        // centre: each hop then stays inside the tight (~28u) opening.
        if (v.birdY() > v.nextGapY() + 45 && v.birdVy() > -250) tap(v, W * 0.5f);
        step(v, c, 1);
    }

    private static String renderDir() {
        String out = System.getProperty("renderDir", "build/renders");
        new File(out).mkdirs();
        return out;
    }

    private static GameView newView() {
        GameView v = new GameView(RuntimeEnvironment.getApplication());
        v.measure(W, H);
        v.layout(0, 0, W, H);
        v.seed(42);
        return v;
    }

    /** Median brightness over a grid of points: the background, even if pillars cross it. */
    private static float backgroundLuminance(Bitmap bmp) {
        java.util.ArrayList<Float> l = new java.util.ArrayList<>();
        for (int x = 20; x < W; x += 70) {
            for (int y = 200; y < H - 400; y += 100) l.add(luminance(bmp.getPixel(x, y)));
        }
        java.util.Collections.sort(l);
        return l.get(l.size() / 2);
    }

    private static float luminance(int argb) {
        return (0.299f * ((argb >> 16) & 0xFF) + 0.587f * ((argb >> 8) & 0xFF)
                + 0.114f * (argb & 0xFF)) / 255f;
    }

    private static void step(GameView v, Canvas c, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            Thread.sleep(16);
            v.draw(c);
        }
    }

    private static void tap(GameView v, float x) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, H / 2f, 0);
        v.onTouchEvent(e);
        e.recycle();
        e = MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, x, H / 2f, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    private static void save(Bitmap b, String path) throws Exception {
        try (FileOutputStream fo = new FileOutputStream(path)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, fo);
        }
    }
}
