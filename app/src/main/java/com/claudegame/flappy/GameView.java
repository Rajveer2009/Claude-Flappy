package com.claudegame.flappy;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/**
 * Claude Flappy — tap to keep Claude's orange spark aloft and thread it through the gaps
 * between stacks of buggy code.
 *
 * All sizes are expressed in "units" (u), where 160u == screen height, so the game plays
 * the same on every device. Everything is drawn with Canvas primitives.
 */
public class GameView extends View {

    // ---------------------------------------------------------------- tuning
    private static final float GRAVITY = 270f;          // u/s^2
    private static final float FLAP_VELOCITY = 80f;     // u/s, upwards
    private static final float MAX_FALL = 120f;         // u/s
    private static final float START_SPEED = 44f;       // u/s
    private static final float MAX_SPEED = 60f;
    private static final float PIPE_W = 17f;
    private static final float PIPE_SPACING = 64f;      // between pipe left edges
    private static final float START_GAP = 40f;         // opening between top and bottom pillar
    private static final float MIN_GAP = 32f;
    private static final float BODY_R = 7f;             // spark ray length
    private static final float HIT_R = 4.6f;            // forgiving collision radius
    private static final float GROUND_H = 18f;
    private static final int LEVEL_EVERY = 10;          // banner + chime
    private static final int NIGHT_EVERY = 30;          // score between day/night swaps

    private static final String[] OOPS = {
            "I flew too close to the bug.",
            "Context window collapsed.",
            "That gap was a hallucination.",
            "I apologize for the crash.",
            "Let me reconsider my altitude.",
            "Stack overflow. Literally.",
            "You're absolutely right.",
            "Gravity was not in my training.",
            "Unexpected pillar in output.",
            "Out of tokens, out of air.",
            "I should have flapped sooner.",
            "Exception thrown. By me.",
            "Let me try a different path.",
            "My reasoning had a flaw.",
            "Hit a rate limit mid-flight.",
            "Good catch. That was a bug.",
            "Retrying with more care...",
            "Segfault at 30,000 feet.",
    };
    private static final String[] BANNERS = {
            "Extended thinking!", "Tokens flowing!", "Thinking faster...", "Deep context!",
    };
    private static final String[] LABELS = {"ERR", "404", "NaN", "{ }", "!", "500", "null"};

    private enum State { READY, RUNNING, PAUSED, OVER }

    private static class Pipe {
        float x, gapY, gap, phase, amp;  // gapY/gap in px; amp in u (moving gaps)
        boolean scored;
        String topLabel, bottomLabel;

        float gapCenter() {
            return gapY + (float) Math.sin(phase) * amp;
        }
    }

    private static class Token {
        float x, y, phase;
        boolean taken;
    }

    private static class Particle {
        float x, y, vx, vy, life, max, size;
        int color;
    }

    private static class Deco {
        float x, y, size, depth;
        int kind;
        String glyph;
    }

    // ---------------------------------------------------------------- state
    private final Random rnd = new Random();
    private final ArrayList<Pipe> pipes = new ArrayList<>();
    private final ArrayList<Token> tokens = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Deco> decos = new ArrayList<>();

    private State state = State.READY;
    private float W, H, u, groundY, birdX, birdY, vy, angle;
    private float speed = START_SPEED, groundScroll, nextPipeIn;
    private int score, tokenCount, hiScore, lastLevel;
    private boolean newBest, landed;
    private float spin, wingPhase, flapKick, blinkTimer = 2f, idleTime, overTime, nightT, flashTime;
    private String oopsLine = OOPS[0];
    private String banner;
    private float bannerTime;
    private long lastFrameNs;
    private boolean hostActive = true;
    /** True while the phone's system-wide dark theme is on. */
    private boolean systemDark;
    private final Sfx sfx;
    private final Music music;
    private final RectF musicBtn = new RectF();
    private final RectF muteBtn = new RectF();
    /** Areas covered by a camera notch / punch hole; the HUD stays clear of them. */
    private float safeL, safeR, safeT;
    private int tokenStreak;
    private float sinceToken = 99f;

    private final SharedPreferences prefs;

    // ---------------------------------------------------------------- paints
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();

    // palette (light, dark)
    private static final int ORANGE = 0xFFD97757;
    private static final int ORANGE_DARK = 0xFFBD5D3A;
    private static final int INK = 0xFF1F1E1D;
    private static final int[] BG = {0xFFF5F0E8, 0xFF1F1E1D};
    private static final int[] FG = {0xFF3D3929, 0xFFE8E2D6};
    private static final int[] FAINT = {0xFFE6DDCF, 0xFF2D2B28};
    private static final int[] CLOUD = {0xFFEAE3D7, 0xFF33312D};
    private static final int[] TERM = {0xFF2A2825, 0xFF3A3834};
    private static final int[] TERM_EDGE = {0xFF3D3A35, 0xFF4A4740};
    private static final int[] GROUND = {0xFFEDE5D8, 0xFF282624};
    private static final int[] WING = {0xFFFFF8F0, 0xFFF5F0E8};

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        prefs = context.getSharedPreferences("claude_flappy", Context.MODE_PRIVATE);
        hiScore = prefs.getInt("hi", 0);
        systemDark = isDark(context.getResources().getConfiguration());
        nightT = systemDark ? 1f : 0f; // start in the right palette, no fade on launch
        sfx = new Sfx(context, prefs.getBoolean("muted", false));
        music = new Music(prefs.getBoolean("music", true));

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        title.setTextAlign(Paint.Align.CENTER);
    }

    // ================================================================ lifecycle

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        W = w;
        H = h;
        u = H / 160f;
        groundY = H - GROUND_H * u;
        birdX = Math.max(W * 0.3f, 18 * u);
        if (state == State.READY) birdY = H * 0.45f;
        decos.clear();
        for (int i = 0; i < 8; i++) decos.add(makeDeco(rnd.nextFloat() * W));
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        safeL = safeR = safeT = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cut = insets.getDisplayCutout();
            if (cut != null) {
                safeL = cut.getSafeInsetLeft();
                safeR = cut.getSafeInsetRight();
                safeT = cut.getSafeInsetTop();
            }
        }
        return super.onApplyWindowInsets(insets);
    }

    /** Called by the activity when it is destroyed. */
    void release() {
        sfx.release();
        music.release();
    }

    // ---- package-private hooks for tests
    Sfx sfx() {
        return sfx;
    }

    Music music() {
        return music;
    }

    /** Makes pillar placement repeatable (tests). */
    void seed(long s) {
        rnd.setSeed(s);
    }

    int score() {
        return score;
    }

    boolean isOver() {
        return state == State.OVER;
    }

    boolean isRunning() {
        return state == State.RUNNING;
    }

    float birdY() {
        return birdY;
    }

    float birdVy() {
        return vy * u;
    }

    /** Centre of the next gap the bird has to fly through (px), or mid-screen if none yet. */
    float nextGapY() {
        for (Pipe p : pipes) {
            if (p.x + PIPE_W * u > birdX - HIT_R * u) return p.gapCenter();
        }
        return H * 0.45f;
    }

    private void toggleMusic() {
        music.setEnabled(!music.isEnabled());
        prefs.edit().putBoolean("music", music.isEnabled()).apply();
        invalidate();
    }

    private void toggleMute() {
        sfx.setMuted(!sfx.isMuted());
        prefs.edit().putBoolean("muted", sfx.isMuted()).apply();
        if (!sfx.isMuted()) sfx.play(Sfx.TOKEN);
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Fires when the user flips dark mode while the game is open; the palette fades over.
        systemDark = isDark(newConfig);
        invalidate();
    }

    private static boolean isDark(Configuration c) {
        return (c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    void onHostPause() {
        hostActive = false;
        music.stop();
        if (state == State.RUNNING) state = State.PAUSED;
    }

    void onHostResume() {
        hostActive = true;
        music.start();
        lastFrameNs = 0;
        invalidate();
    }

    private void resetGame() {
        pipes.clear();
        tokens.clear();
        particles.clear();
        birdY = H * 0.45f;
        vy = 0;
        angle = 0;
        speed = START_SPEED;
        score = tokenCount = 0;
        lastLevel = 0;
        nextPipeIn = 30f; // u of travel before the first pipe appears off screen
        newBest = landed = false;
        banner = null;
        tokenStreak = 0;
        state = State.RUNNING;
        sfx.play(Sfx.START);
        music.setDucked(false);
    }

    // ================================================================ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int idx = e.getActionIndex();
            press(e.getX(idx), e.getY(idx));
            return true;
        }
        return action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL
                || super.onTouchEvent(e);
    }

    private void press(float x, float y) {
        if (state != State.RUNNING && muteBtn.contains(x, y)) {
            toggleMute();
            return;
        }
        if (state != State.RUNNING && musicBtn.contains(x, y)) {
            toggleMusic();
            return;
        }
        action();
    }

    /** The one button: start, resume, flap or retry depending on the state. */
    private void action() {
        switch (state) {
            case READY:
                resetGame();
                flap();
                break;
            case PAUSED:
                state = State.RUNNING;
                lastFrameNs = 0;
                break;
            case RUNNING:
                flap();
                break;
            case OVER:
                if (overTime > 0.8f) resetGame();
                break;
        }
        invalidate();
    }

    private void pause() {
        state = State.PAUSED;
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getRepeatCount() > 0) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                action();
                return true;
            case KeyEvent.KEYCODE_P:
            case KeyEvent.KEYCODE_ESCAPE:
                if (state == State.RUNNING) pause();
                else if (state == State.PAUSED) action();
                return true;
            case KeyEvent.KEYCODE_M:
                toggleMute();
                return true;
            case KeyEvent.KEYCODE_N:
                toggleMusic();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void flap() {
        if (state != State.RUNNING) return;
        vy = -FLAP_VELOCITY;
        wingPhase = 0f;
        flapKick = 1f;
        puff(birdX - 4 * u, birdY + 2 * u, 4, mix(FAINT), -1);
        sfx.play(Sfx.FLAP, 0.95f + rnd.nextFloat() * 0.1f);
    }

    // ================================================================ update

    private void update(float dt) {
        idleTime += dt;
        blinkTimer -= dt;
        if (blinkTimer < -0.14f) blinkTimer = 2f + rnd.nextFloat() * 3f;
        if (bannerTime > 0) bannerTime -= dt;
        sinceToken += dt;
        if (flashTime > 0) flashTime -= dt;
        if (flapKick > 0) flapKick = Math.max(0, flapKick - dt * 4f);
        wingPhase += dt * (state == State.RUNNING ? 18f : 10f);

        // Dark system theme keeps the game dark; otherwise day and night alternate with score.
        boolean cycleNight = (state == State.RUNNING || state == State.OVER || state == State.PAUSED)
                && (score / NIGHT_EVERY) % 2 == 1;
        float night = systemDark || cycleNight ? 1f : 0f;
        nightT += (night - nightT) * Math.min(1f, dt * 1.5f);

        updateParticles(dt);
        if (state == State.PAUSED) return;

        if (state == State.READY) {
            // hover and bob while waiting for the first tap
            birdY = H * 0.45f + (float) Math.sin(idleTime * 3f) * 2.5f * u;
            angle = (float) Math.sin(idleTime * 3f + 1) * 6f;
            spin += dt * 40f;
            scrollWorld(START_SPEED * 0.5f * dt);
            return;
        }

        if (state == State.OVER) {
            overTime += dt;
            // the spark tumbles down onto the ground after a crash
            if (!landed) {
                vy = Math.min(MAX_FALL * 1.4f, vy + GRAVITY * 1.3f * dt);
                birdY += vy * dt * u;
                angle = Math.min(90f, angle + 400f * dt);
                if (birdY + HIT_R * u >= groundY) {
                    birdY = groundY - HIT_R * u;
                    landed = true;
                    puff(birdX, groundY, 8, mix(FG), 0);
                }
            }
            return;
        }

        // --- bird physics
        vy = Math.min(MAX_FALL, vy + GRAVITY * dt);
        birdY += vy * dt * u;
        if (birdY < HIT_R * u) { // bump the ceiling, no crash
            birdY = HIT_R * u;
            vy = Math.max(vy, 0);
        }
        float target = vy < 0 ? -22f : Math.min(80f, -22f + vy * 0.9f);
        angle += (target - angle) * Math.min(1f, dt * (vy < 0 ? 14f : 5f));
        spin += dt * (90f + flapKick * 600f);

        // --- world
        speed = Math.min(MAX_SPEED, START_SPEED + score * 0.35f);
        float d = speed * dt;
        scrollWorld(d);
        float dx = d * u;
        for (Iterator<Pipe> it = pipes.iterator(); it.hasNext(); ) {
            Pipe p = it.next();
            p.x -= dx;
            p.phase += dt * 1.6f;
            if (p.x + PIPE_W * u < -4 * u) it.remove();
        }
        for (Iterator<Token> it = tokens.iterator(); it.hasNext(); ) {
            Token t = it.next();
            t.x -= dx;
            t.phase += dt;
            if (t.x < -10 * u || t.taken) it.remove();
        }
        nextPipeIn -= d;
        if (nextPipeIn <= 0) {
            spawnPipe();
            nextPipeIn += PIPE_SPACING;
        }

        // --- scoring
        for (Pipe p : pipes) {
            if (!p.scored && p.x + PIPE_W * u < birdX) {
                p.scored = true;
                addScore(1);
                sfx.play(Sfx.SCORE);
            }
        }
        float r = HIT_R * u;
        for (Token t : tokens) {
            float ty = tokenY(t);
            float tr = 3.2f * u;
            if (!t.taken && dist2(birdX, birdY, t.x, ty) < (r + tr) * (r + tr)) {
                t.taken = true;
                tokenCount++;
                addScore(1);
                puff(t.x, ty, 8, ORANGE, 0);
                // tokens grabbed in quick succession chime higher and higher
                tokenStreak = sinceToken < 2.5f ? Math.min(tokenStreak + 1, 6) : 0;
                sinceToken = 0f;
                sfx.play(Sfx.TOKEN, (float) Math.pow(2, tokenStreak / 6.0));
            }
        }

        // --- collisions
        if (birdY + r >= groundY) {
            birdY = groundY - r;
            crash(true);
            return;
        }
        for (Pipe p : pipes) {
            float gc = p.gapCenter();
            float top = gc - p.gap / 2, bottom = gc + p.gap / 2;
            float inset = 0.6f * u;
            if (circleHitsRect(birdX, birdY, r, p.x + inset, -H, p.x + PIPE_W * u - inset, top)
                    || circleHitsRect(birdX, birdY, r, p.x + inset, bottom, p.x + PIPE_W * u - inset, groundY)) {
                crash(false);
                return;
            }
        }
    }

    private void addScore(int n) {
        score += n;
        int level = score / LEVEL_EVERY;
        if (level > lastLevel) {
            lastLevel = level;
            showBanner(BANNERS[(level - 1) % BANNERS.length]);
            sfx.play(Sfx.LEVEL);
            flashTime = 0.6f;
        }
    }

    private void scrollWorld(float d) {
        groundScroll += d * u;
        for (int i = 0; i < decos.size(); i++) {
            Deco dc = decos.get(i);
            dc.x -= d * dc.depth * u;
            if (dc.x < -30 * u) decos.set(i, makeDeco(W + rnd.nextFloat() * 40 * u));
        }
    }

    private void spawnPipe() {
        Pipe prev = pipes.isEmpty() ? null : pipes.get(pipes.size() - 1);
        Pipe p = new Pipe();
        p.x = W + 4 * u;
        float t = Math.min(1f, score / 40f);
        p.gap = (START_GAP + (MIN_GAP - START_GAP) * t) * u;
        // gaps drift up and down from score 15 on
        if (score >= 15 && rnd.nextFloat() < 0.4f) {
            p.amp = 3f + rnd.nextFloat() * Math.min(7f, (score - 15) * 0.3f + 2f);
            p.phase = rnd.nextFloat() * 6.28f;
        }
        float margin = 14 * u + p.amp * u;
        float lo = margin + p.gap / 2, hi = groundY - margin - p.gap / 2;
        float y = lo + rnd.nextFloat() * Math.max(0, hi - lo);
        if (prev != null) { // keep consecutive gaps reachable
            float maxStep = 34 * u;
            y = Math.max(prev.gapY - maxStep, Math.min(prev.gapY + maxStep, y));
        }
        p.gapY = y;
        p.topLabel = LABELS[rnd.nextInt(LABELS.length)];
        p.bottomLabel = LABELS[rnd.nextInt(LABELS.length)];
        pipes.add(p);

        // a token floats between the previous pillar and this one
        if (prev != null && rnd.nextFloat() < 0.4f) {
            Token tk = new Token();
            tk.x = p.x - (PIPE_SPACING - PIPE_W) * 0.5f * u;
            tk.y = (prev.gapY + p.gapY) / 2 + (rnd.nextFloat() - 0.5f) * 10 * u;
            tk.phase = rnd.nextFloat() * 3f;
            tokens.add(tk);
        }
    }

    private void crash(boolean ground) {
        state = State.OVER;
        overTime = 0;
        landed = ground;
        vy = ground ? 0 : -20f; // a little bounce off the pillar
        String prev = oopsLine;
        do {
            oopsLine = OOPS[rnd.nextInt(OOPS.length)];
        } while (oopsLine.equals(prev)); // never the same line twice in a row
        for (int i = 0; i < 26; i++) {
            Particle q = new Particle();
            double a = rnd.nextDouble() * Math.PI * 2;
            float s = (20 + rnd.nextFloat() * 50) * u;
            q.x = birdX;
            q.y = birdY;
            q.vx = (float) Math.cos(a) * s;
            q.vy = (float) Math.sin(a) * s - 20 * u;
            q.life = q.max = 0.6f + rnd.nextFloat() * 0.6f;
            q.size = (0.6f + rnd.nextFloat()) * u;
            q.color = rnd.nextBoolean() ? ORANGE : ORANGE_DARK;
            particles.add(q);
        }
        if (score > hiScore) {
            hiScore = score;
            newBest = true;
            prefs.edit().putInt("hi", hiScore).apply();
        }
        flashTime = 0.35f;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        sfx.play(Sfx.CRASH);
        if (!ground) sfx.play(Sfx.SWOOSH);
        music.setDucked(true); // quieter music on the game over screen
    }

    private void showBanner(String s) {
        banner = s;
        bannerTime = 1.8f;
    }

    private Deco makeDeco(float x) {
        Deco d = new Deco();
        d.x = x;
        d.kind = rnd.nextInt(3); // 0,1 cloud, 2 glyph
        float top = 10 * u, span = Math.max(10 * u, groundY - 30 * u - top);
        d.y = top + rnd.nextFloat() * span;
        if (d.kind == 2) {
            String[] g = {"{ }", "</>", "( )", "=>", "#", "*", "[ ]", "fn"};
            d.glyph = g[rnd.nextInt(g.length)];
            d.size = (4 + rnd.nextFloat() * 3) * u;
            d.depth = 0.25f + rnd.nextFloat() * 0.2f;
        } else {
            d.size = (5 + rnd.nextFloat() * 5) * u;
            d.depth = 0.12f + rnd.nextFloat() * 0.15f;
        }
        return d;
    }

    /** @param dir -1 to blow particles backwards (flap), 0 for an all-round burst */
    private void puff(float x, float y, int n, int color, int dir) {
        for (int i = 0; i < n; i++) {
            Particle q = new Particle();
            q.x = x + (rnd.nextFloat() - 0.5f) * 3 * u;
            q.y = y + (rnd.nextFloat() - 0.5f) * 2 * u;
            q.vx = dir < 0 ? -(10 + rnd.nextFloat() * 20) * u : (rnd.nextFloat() - 0.5f) * 40 * u;
            q.vy = dir < 0 ? (rnd.nextFloat() * 15) * u : -rnd.nextFloat() * 25 * u;
            q.life = q.max = 0.25f + rnd.nextFloat() * 0.3f;
            q.size = (0.5f + rnd.nextFloat() * 0.7f) * u;
            q.color = color;
            particles.add(q);
        }
    }

    private void updateParticles(float dt) {
        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle q = it.next();
            q.life -= dt;
            if (q.life <= 0) { it.remove(); continue; }
            q.vy += 120 * u * dt;
            q.x += q.vx * dt;
            q.y += q.vy * dt;
        }
    }

    // ================================================================ geometry

    private float tokenY(Token t) {
        return t.y + (float) Math.sin(t.phase * 4) * 1.2f * u;
    }

    private static float dist2(float ax, float ay, float bx, float by) {
        return (ax - bx) * (ax - bx) + (ay - by) * (ay - by);
    }

    private static boolean circleHitsRect(float cx, float cy, float r,
                                          float l, float t, float rr, float b) {
        float nx = Math.max(l, Math.min(cx, rr));
        float ny = Math.max(t, Math.min(cy, b));
        return dist2(cx, cy, nx, ny) < r * r;
    }

    // ================================================================ draw

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0 ? 0f : (now - lastFrameNs) / 1e9f;
        lastFrameNs = now;
        if (W > 0) update(Math.min(dt, 1f / 30f));

        int bg = mix(BG), fg = mix(FG);
        c.drawColor(bg);
        drawBackground(c);
        for (Token t : tokens) drawToken(c, t);
        for (Pipe p : pipes) drawPipe(c, p);
        drawGround(c, fg);
        drawBird(c);
        for (Particle q : particles) {
            fill.setColor(q.color);
            fill.setAlpha((int) (255 * Math.max(0, q.life / q.max)));
            c.drawCircle(q.x, q.y, q.size, fill);
        }
        fill.setAlpha(255);
        if (flashTime > 0) {
            fill.setColor(state == State.OVER ? 0xFFFFFFFF : ORANGE);
            fill.setAlpha((int) ((state == State.OVER ? 90 : 40) * Math.min(1f, flashTime / 0.6f)));
            c.drawRect(0, 0, W, H, fill);
            fill.setAlpha(255);
        }
        drawHud(c, fg, bg);

        if (hostActive) postInvalidateOnAnimation();
    }

    private void drawBackground(Canvas c) {
        int faint = mix(FAINT), cloud = mix(CLOUD);
        // sun / moon
        float sx = W * 0.78f, sy = 26 * u;
        fill.setColor(lerpColor(0xFFF0D9C4, 0xFFE8E2D6, nightT));
        c.drawCircle(sx, sy, 8 * u, fill);
        if (nightT > 0.01f) {
            fill.setColor(mix(BG));
            fill.setAlpha((int) (255 * nightT));
            c.drawCircle(sx + 3.5f * u, sy - 2.5f * u, 7 * u, fill);
            fill.setAlpha(255);
            // stars
            fill.setColor(0xFFE8E2D6);
            fill.setAlpha((int) (160 * nightT));
            for (int i = 0; i < 26; i++) {
                float x = ((i * 977) % 1000) / 1000f * W;
                float y = ((i * 571) % 1000) / 1000f * groundY * 0.75f;
                c.drawCircle(x, y, 0.4f * u, fill);
            }
            fill.setAlpha(255);
        }
        for (Deco d : decos) {
            if (d.kind == 2) {
                text.setTextSize(d.size);
                text.setColor(faint);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(d.glyph, d.x, d.y, text);
            } else {
                fill.setColor(cloud);
                float s = d.size;
                rect.set(d.x - s, d.y - s * 0.35f, d.x + s * 1.3f, d.y + s * 0.35f);
                c.drawRoundRect(rect, s * 0.35f, s * 0.35f, fill);
                c.drawCircle(d.x - s * 0.1f, d.y - s * 0.35f, s * 0.45f, fill);
                c.drawCircle(d.x + s * 0.55f, d.y - s * 0.3f, s * 0.35f, fill);
            }
        }
    }

    private void drawGround(Canvas c, int fg) {
        fill.setColor(mix(GROUND));
        c.drawRect(0, groundY, W, H, fill);
        stroke.setColor(fg);
        stroke.setStrokeWidth(0.5f * u);
        c.drawLine(0, groundY, W, groundY, stroke);
        // scrolling dashes generated from a hash of their tile index
        float step = 6 * u;
        long first = (long) Math.floor(groundScroll / step);
        stroke.setStrokeWidth(0.35f * u);
        stroke.setColor(lerpColor(fg, mix(BG), 0.3f));
        for (long k = first; k < first + (long) (W / step) + 2; k++) {
            int hsh = (int) ((k * 2654435761L) >>> 8) & 0xFFFF;
            float x = k * step - groundScroll + (hsh % 100) / 100f * step;
            float y = groundY + (2 + (hsh >> 8) % 5) * u;
            float len = (0.5f + (hsh % 7) * 0.4f) * u;
            if (hsh % 3 != 0) c.drawLine(x, y, x + len, y, stroke);
        }
    }

    private void drawToken(Canvas c, Token t) {
        float y = tokenY(t);
        float r = 2.8f * u;
        float squash = (float) Math.abs(Math.cos(t.phase * 2.5f));
        fill.setColor(ORANGE);
        rect.set(t.x - r * Math.max(0.2f, squash), y - r, t.x + r * Math.max(0.2f, squash), y + r);
        c.drawOval(rect, fill);
        if (squash > 0.4f) {
            fill.setColor(0xFFFFF4EC);
            float s = r * 0.55f * squash;
            path.reset();
            path.moveTo(t.x, y - s);
            path.quadTo(t.x, y, t.x + s, y);
            path.quadTo(t.x, y, t.x, y + s);
            path.quadTo(t.x, y, t.x - s, y);
            path.quadTo(t.x, y, t.x, y - s);
            c.drawPath(path, fill);
        }
    }

    /** A pillar of stacked code: one hanging from the top, one standing on the ground. */
    private void drawPipe(Canvas c, Pipe p) {
        float gc = p.gapCenter();
        float top = gc - p.gap / 2, bottom = gc + p.gap / 2;
        float w = PIPE_W * u;
        drawColumn(c, p.x, -2 * u, w, top + 2 * u, true, p.topLabel);
        drawColumn(c, p.x, bottom, w, groundY - bottom + 2 * u, false, p.bottomLabel);
    }

    private void drawColumn(Canvas c, float x, float y, float w, float h, boolean hanging, String label) {
        if (h <= 0) return;
        float r = 1.4f * u;
        int body = mix(TERM);
        // body
        fill.setColor(body);
        rect.set(x + 0.8f * u, y, x + w - 0.8f * u, y + h);
        c.drawRect(rect, fill);
        // code lines inside the column
        stroke.setStrokeWidth(0.6f * u);
        stroke.setColor(0xFF5A5650);
        float capH = 7 * u;
        float lineTop = hanging ? y + 2 * u : y + capH + 2 * u;
        float lineBot = hanging ? y + h - capH - 1.5f * u : y + h - 2 * u;
        for (float ly = lineTop; ly < lineBot; ly += 2.4f * u) {
            int k = (int) (Math.abs(ly - y) / u);
            float indent = (k * 7 % 3) * 1.5f * u;
            float len = (3f + (k * 37 % 6)) * u;
            float lx = x + 2.6f * u + indent;
            float end = Math.min(lx + len, x + w - 2.6f * u);
            if (k % 5 != 4 && end > lx) c.drawLine(lx, ly, end, ly, stroke);
        }
        // cap: a terminal window header at the open end of the pillar
        float cy0 = hanging ? y + h - capH : y;
        fill.setColor(mix(TERM_EDGE));
        rect.set(x - 0.6f * u, cy0, x + w + 0.6f * u, cy0 + capH);
        c.drawRoundRect(rect, r, r, fill);
        stroke.setColor(ORANGE);
        stroke.setStrokeWidth(0.5f * u);
        c.drawRoundRect(rect, r, r, stroke);
        float dotY = cy0 + 1.8f * u;
        fill.setColor(ORANGE);
        c.drawCircle(x + 1.8f * u, dotY, 0.55f * u, fill);
        fill.setColor(0xFFE0C08A);
        c.drawCircle(x + 3.4f * u, dotY, 0.55f * u, fill);
        fill.setColor(0xFF9DB38A);
        c.drawCircle(x + 5.0f * u, dotY, 0.55f * u, fill);
        text.setColor(ORANGE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(label.length() > 2 ? 2.6f * u : 3.2f * u);
        c.drawText(label, x + w / 2, cy0 + capH - 1.3f * u, text);
    }

    private void drawBird(Canvas c) {
        boolean dead = state == State.OVER;
        c.save();
        c.translate(birdX, birdY);
        c.rotate(angle);

        // the spark: 12 rounded rays with alternating lengths, spinning gently
        stroke.setColor(ORANGE);
        stroke.setStrokeWidth(2.3f * u);
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30 + spin);
            float len = (i % 2 == 0 ? BODY_R - 1.1f : BODY_R * 0.74f - 1.1f) * u;
            len *= 1f + flapKick * 0.12f;
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            c.drawLine(cos * 2 * u, sin * 2 * u, cos * len, sin * len, stroke);
        }
        fill.setColor(ORANGE);
        c.drawCircle(0, 0, 4.0f * u, fill);

        // wing: a little cream flap on the spark's back
        float flapAngle = dead ? 30f : (float) Math.sin(wingPhase) * 35f;
        c.save();
        c.translate(-2.2f * u, 0.6f * u);
        c.rotate(flapAngle);
        fill.setColor(mix(WING));
        rect.set(-5.2f * u, -1.6f * u, 0.4f * u, 1.6f * u);
        c.drawOval(rect, fill);
        stroke.setColor(INK);
        stroke.setStrokeWidth(0.35f * u);
        c.drawOval(rect, stroke);
        c.restore();

        // face, looking ahead (to the right)
        float ex1 = 0.6f * u, ex2 = 2.6f * u, ey = -0.8f * u;
        if (dead) {
            stroke.setColor(INK);
            stroke.setStrokeWidth(0.42f * u);
            float s = 0.7f * u;
            for (float x0 : new float[]{ex1, ex2}) {
                c.drawLine(x0 - s, ey - s, x0 + s, ey + s, stroke);
                c.drawLine(x0 - s, ey + s, x0 + s, ey - s, stroke);
            }
            fill.setColor(INK);
            c.drawCircle(1.6f * u, 1.7f * u, 0.55f * u, fill);
        } else if (blinkTimer < 0) {
            stroke.setColor(INK);
            stroke.setStrokeWidth(0.42f * u);
            c.drawLine(ex1 - 0.6f * u, ey, ex1 + 0.6f * u, ey, stroke);
            c.drawLine(ex2 - 0.6f * u, ey, ex2 + 0.6f * u, ey, stroke);
        } else {
            fill.setColor(INK);
            rect.set(ex1 - 0.6f * u, ey - 0.95f * u, ex1 + 0.6f * u, ey + 0.95f * u);
            c.drawOval(rect, fill);
            rect.set(ex2 - 0.6f * u, ey - 0.95f * u, ex2 + 0.6f * u, ey + 0.95f * u);
            c.drawOval(rect, fill);
            fill.setColor(0xFFFFFFFF);
            c.drawCircle(ex1 + 0.22f * u, ey - 0.38f * u, 0.24f * u, fill);
            c.drawCircle(ex2 + 0.22f * u, ey - 0.38f * u, 0.24f * u, fill);
            stroke.setColor(INK);
            stroke.setStrokeWidth(0.33f * u);
            rect.set(0.6f * u, 0.3f * u, 2.6f * u, 1.8f * u);
            c.drawArc(rect, 20, 140, false, stroke);
        }
        fill.setColor(0x55FFFFFF);
        c.drawCircle(3.3f * u, 0.9f * u, 0.6f * u, fill);
        c.restore();
    }

    private void drawHud(Canvas c, int fg, int bg) {
        float pad = 4 * u;
        // keep the HUD clear of a camera notch / punch hole
        float padR = Math.max(pad, safeR + 2 * u);
        float padT = Math.max(pad, safeT + 1 * u);

        // best score, top right
        text.setTextSize(3.6f * u);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(lerpColor(0xFF9B9382, 0xFF8A8478, nightT));
        c.drawText("HI " + hiScore, W - padR, padT + 3.6f * u, text);

        if (state != State.READY) {
            // big score, centred
            title.setColor(fg);
            title.setTextSize(13 * u);
            c.drawText(Integer.toString(score), W / 2, padT + 13 * u, title);
            // token counter under the best score
            fill.setColor(ORANGE);
            text.setColor(fg);
            String tc = "x " + tokenCount;
            float tw = text.measureText(tc);
            c.drawText(tc, W - padR, padT + 9 * u, text);
            c.drawCircle(W - padR - tw - 2.4f * u, padT + 7.8f * u, 1.7f * u, fill);
        }

        // sound toggles, only on menus so they can't be hit by accident mid-flight
        if (state != State.RUNNING) {
            float by = groundY + GROUND_H * u * 0.5f;
            drawMuteButton(c, fg, W - padR - 5 * u, by);
            drawMusicButton(c, fg, W - padR - 17 * u, by);
        } else {
            muteBtn.setEmpty();
            musicBtn.setEmpty();
        }

        if (banner != null && bannerTime > 0 && state == State.RUNNING) {
            title.setColor(ORANGE);
            title.setAlpha((int) (255 * Math.min(1f, bannerTime / 0.4f)));
            fitTitle(banner, 6 * u);
            c.drawText(banner, W / 2, padT + 22 * u, title);
            title.setAlpha(255);
        }

        switch (state) {
            case READY:
                title.setColor(fg);
                fitTitle("Claude Flappy", 13 * u);
                c.drawText("Claude Flappy", W / 2, 40 * u, title);
                title.setColor(ORANGE);
                fitTitle("Tap to flap", 5 * u);
                c.drawText("Tap to flap", W / 2, 50 * u + (float) Math.sin(idleTime * 4) * 0.8f * u, title);
                drawHint(c, fg);
                break;
            case PAUSED:
                overlay(c, bg);
                title.setColor(fg);
                title.setTextSize(11 * u);
                c.drawText("Paused", W / 2, H * 0.42f, title);
                title.setColor(ORANGE);
                fitTitle("Tap to continue", 5 * u);
                c.drawText("Tap to continue", W / 2, H * 0.42f + 9 * u, title);
                break;
            case OVER:
                if (overTime > 0.3f) drawGameOver(c, fg, bg);
                break;
            default:
                break;
        }
    }

    private void drawGameOver(Canvas c, int fg, int bg) {
        overlay(c, bg);
        float a = Math.min(1f, (overTime - 0.3f) / 0.3f);
        float cy = H * 0.40f + (1 - a) * 6 * u;
        title.setColor(fg);
        fitTitle(oopsLine, 7 * u);
        c.drawText(oopsLine, W / 2, cy - 16 * u, title);

        // score card
        float cw = Math.min(W * 0.8f, 66 * u), ch = 26 * u;
        rect.set(W / 2 - cw / 2, cy - 8 * u, W / 2 + cw / 2, cy - 8 * u + ch);
        fill.setColor(mix(FAINT));
        c.drawRoundRect(rect, 3 * u, 3 * u, fill);
        stroke.setColor(ORANGE);
        stroke.setStrokeWidth(0.5f * u);
        c.drawRoundRect(rect, 3 * u, 3 * u, stroke);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(3.2f * u);
        text.setColor(lerpColor(fg, bg, 0.4f));
        float lx = W / 2 - cw / 4, rx = W / 2 + cw / 4;
        c.drawText("SCORE", lx, rect.top + 7 * u, text);
        c.drawText("BEST", rx, rect.top + 7 * u, text);
        title.setColor(fg);
        title.setTextSize(10 * u);
        c.drawText(Integer.toString(score), lx, rect.top + 19 * u, title);
        title.setColor(newBest ? ORANGE : fg);
        c.drawText(Integer.toString(hiScore), rx, rect.top + 19 * u, title);
        if (newBest) {
            text.setColor(ORANGE);
            text.setTextSize(2.8f * u);
            c.drawText("NEW!", rx, rect.top + 23.5f * u, text);
        }
        text.setColor(lerpColor(fg, bg, 0.4f));
        text.setTextSize(2.8f * u);
        c.drawText(tokenCount + (tokenCount == 1 ? " token" : " tokens"), lx, rect.top + 23.5f * u, text);

        if (overTime > 0.8f) {
            title.setColor(ORANGE);
            fitTitle("Tap to try again", 5 * u);
            c.drawText("Tap to try again", W / 2,
                    rect.bottom + 10 * u + (float) Math.sin(idleTime * 4) * 0.6f * u, title);
        }
    }

    private void drawMuteButton(Canvas c, int fg, float cx, float cy) {
        float r = 4.5f * u;
        muteBtn.set(cx - r * 1.3f, cy - r * 1.3f, cx + r * 1.3f, cy + r * 1.3f); // generous hit area
        stroke.setColor(lerpColor(fg, mix(BG), 0.5f));
        stroke.setStrokeWidth(0.4f * u);
        c.drawCircle(cx, cy, r, stroke);
        // speaker
        fill.setColor(fg);
        path.reset();
        float x0 = cx - 2.4f * u;
        path.moveTo(x0, cy - 0.9f * u);
        path.lineTo(x0 + 1.1f * u, cy - 0.9f * u);
        path.lineTo(x0 + 2.6f * u, cy - 2.2f * u);
        path.lineTo(x0 + 2.6f * u, cy + 2.2f * u);
        path.lineTo(x0 + 1.1f * u, cy + 0.9f * u);
        path.lineTo(x0, cy + 0.9f * u);
        path.close();
        c.drawPath(path, fill);
        stroke.setColor(fg);
        stroke.setStrokeWidth(0.45f * u);
        if (sfx.isMuted()) {
            float m = 0.9f * u, mx = cx + 1.6f * u;
            c.drawLine(mx - m, cy - m, mx + m, cy + m, stroke);
            c.drawLine(mx - m, cy + m, mx + m, cy - m, stroke);
        } else {
            rect.set(cx - 0.6f * u, cy - 1.4f * u, cx + 1.4f * u, cy + 1.4f * u);
            c.drawArc(rect, -50, 100, false, stroke);
            rect.set(cx - 1.4f * u, cy - 2.5f * u, cx + 2.6f * u, cy + 2.5f * u);
            c.drawArc(rect, -50, 100, false, stroke);
        }
    }

    private void drawMusicButton(Canvas c, int fg, float cx, float cy) {
        float r = 4.5f * u;
        musicBtn.set(cx - r * 1.3f, cy - r * 1.3f, cx + r * 1.3f, cy + r * 1.3f);
        stroke.setColor(lerpColor(fg, mix(BG), 0.5f));
        stroke.setStrokeWidth(0.4f * u);
        c.drawCircle(cx, cy, r, stroke);
        // two beamed eighth notes
        fill.setColor(fg);
        stroke.setColor(fg);
        stroke.setStrokeWidth(0.45f * u);
        float lx = cx - 1.5f * u, rx = cx + 1.7f * u, by = cy + 1.6f * u, top = cy - 2.3f * u;
        rect.set(lx - 1.5f * u, by - 0.9f * u, lx + 0.4f * u, by + 0.9f * u);
        c.drawOval(rect, fill);
        rect.set(rx - 1.5f * u, by - 1.3f * u, rx + 0.4f * u, by + 0.5f * u);
        c.drawOval(rect, fill);
        c.drawLine(lx + 0.2f * u, by, lx + 0.2f * u, top + 0.3f * u, stroke);
        c.drawLine(rx + 0.2f * u, by - 0.4f * u, rx + 0.2f * u, top - 0.3f * u, stroke);
        stroke.setStrokeWidth(0.9f * u);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        c.drawLine(lx + 0.2f * u, top + 0.6f * u, rx + 0.2f * u, top, stroke);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        if (!music.isEnabled()) {
            // strike-through when the music is off
            stroke.setStrokeWidth(0.45f * u);
            c.drawLine(cx - 3.2f * u, cy + 3.2f * u, cx + 3.2f * u, cy - 3.2f * u, stroke);
        }
    }

    private void drawHint(Canvas c, int fg) {
        text.setTextAlign(Paint.Align.CENTER);
        String a = "Tap anywhere to flap", b = "Fly through the gaps. Grab tokens.";
        fitText(b, 3.2f * u);
        text.setColor(lerpColor(fg, mix(BG), 0.35f));
        c.drawText(a, W / 2, groundY - 16 * u, text);
        text.setColor(lerpColor(fg, mix(BG), 0.55f));
        c.drawText(b, W / 2, groundY - 11 * u, text);
    }

    private void overlay(Canvas c, int bg) {
        fill.setColor(bg);
        fill.setAlpha(120);
        c.drawRect(0, 0, W, H, fill);
        fill.setAlpha(255);
    }

    // ================================================================ helpers

    /** Sets the title paint's size, shrinking it if needed so the line fits the screen. */
    private void fitTitle(String s, float size) {
        title.setTextSize(size);
        float w = title.measureText(s);
        if (w > W * 0.9f) title.setTextSize(size * W * 0.9f / w);
    }

    private void fitText(String s, float size) {
        text.setTextSize(size);
        float w = text.measureText(s);
        if (w > W * 0.92f) text.setTextSize(size * W * 0.92f / w);
    }

    private int mix(int[] pair) {
        return lerpColor(pair[0], pair[1], nightT);
    }

    private static int lerpColor(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a), aa = Color.alpha(a);
        return Color.argb(
                (int) (aa + (Color.alpha(b) - aa) * t),
                (int) (ar + (Color.red(b) - ar) * t),
                (int) (ag + (Color.green(b) - ag) * t),
                (int) (ab + (Color.blue(b) - ab) * t));
    }
}
