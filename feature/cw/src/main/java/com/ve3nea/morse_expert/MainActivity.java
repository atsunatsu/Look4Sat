
package com.ve3nea.morse_expert;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.rtbishop.look4sat.feature.cw.R;
import E2.g;
import H2.a;
import H2.b;
import j1.C1646n;

/**
 * Ported verbatim from Morse Expert 1.15 MainActivity (com.ve3nea.morse_expert); obfuscated
 * class/field names kept as-is. Surgery notes: originally extends e.AbstractActivityC1557l
 * (obfuscated AppCompat base) -> now a plain controller class, Activity injected via
 * onCreate(Activity, ConstraintLayout) for Compose AndroidView integration. Ads (d1.*),
 * billing (I2.a), premium/rate menu removed per user. h3.b.r (recursive findViewById),
 * K1.a.M (immersive status bar), B.i (permissions) replaced with equivalent standard APIs.
 * Decoder logic (H2.a audio, H2.b core, pas engine) untouched.
 */
public class MainActivity {

    public static final /* synthetic */ int f11034K = 0;

    /* renamed from: D, reason: collision with root package name */
    public C1646n f11035D;

    /* renamed from: E, reason: collision with root package name */
    public a f11036E;

    /* renamed from: F, reason: collision with root package name */
    public b f11037F;

    /* renamed from: G, reason: collision with root package name */
    public long f11038G = 0;

    /* renamed from: J, reason: collision with root package name */
    public final g f11040J = new g(this, 1);

    /** Injected Activity context (replaces the original this Activity capabilities) */
    public android.app.Activity mActivity;

    public void onCreate(android.app.Activity activity, ConstraintLayout constraintLayout) {
        onCreate(activity, constraintLayout, true);
    }

    /**
     * applyImmersive=false 用于嵌入小容器(如转发器 CW 面板):
     * 不触碰宿主窗口的系统栏标志(避免影响整个 Activity)。
     */
    public void onCreate(android.app.Activity activity, ConstraintLayout constraintLayout, boolean applyImmersive) {
        this.mActivity = activity;
        if (applyImmersive) {
            // K1.a.M(getWindow(), false): immersive status bar (nav/fullscreen flags, ported as-is)
            setImmersive(activity.getWindow(), false);
        }
        View inflate = constraintLayout;
        int i4 = R.id.decodedTextView;
        DecodedTextView decodedTextView = (DecodedTextView) findViewRecursive(inflate, R.id.decodedTextView);
        if (decodedTextView != null) {
            i4 = R.id.scaleView;
            ScaleView scaleView = (ScaleView) findViewRecursive(inflate, R.id.scaleView);
            if (scaleView != null) {
                i4 = R.id.statusTextView;
                TextView textView = (TextView) findViewRecursive(inflate, R.id.statusTextView);
                if (textView != null) {
                    i4 = R.id.verticalLayout;
                    LinearLayout linearLayout = (LinearLayout) findViewRecursive(inflate, R.id.verticalLayout);
                    if (linearLayout != null) {
                        i4 = R.id.waterfallView;
                        WaterfallSurfaceView waterfallSurfaceView = (WaterfallSurfaceView) findViewRecursive(inflate, R.id.waterfallView);
                        if (waterfallSurfaceView != null) {
                            this.f11035D = new C1646n(constraintLayout, decodedTextView, scaleView, textView, linearLayout, waterfallSurfaceView);
                            ((WaterfallSurfaceView) this.f11035D.f11892k).setOnTouchListener(this.f11040J);
                            // Originally: permission check then v(); permission flow handled by the Compose page
                            // Originally: ad banner mounting (removed)
                            return;
                        }
                    }
                }
            }
        }
        throw new NullPointerException("Missing required view with ID: " + inflate.getResources().getResourceName(i4));
    }

    /** Original h3.b.r: recursive findViewById (ported behavior) */
    private static View findViewRecursive(View view, int id) {
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            int childCount = viewGroup.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View found = viewGroup.getChildAt(i).findViewById(id);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        return null;
    }

    /** Original K1.a.M(window, false): immersive (hide nav/fullscreen flags, ported as-is) */
    private static void setImmersive(Window window, boolean immersive) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(!immersive);
            if (immersive) {
                window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
            } else {
                window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            }
        } else {
            View decorView = window.getDecorView();
            int systemUiVisibility = decorView.getSystemUiVisibility();
            decorView.setSystemUiVisibility(immersive ? (systemUiVisibility & (-1793)) : (systemUiVisibility | 1792));
        }
    }

    /** Original v(): audio capture + decoder core init (logic unchanged) */
    public void v() {
        this.f11036E = new a();
        b bVar = new b(mActivity.getApplicationContext());
        this.f11037F = bVar;
        this.f11036E.f601l = bVar;
        bVar.f619o = (WaterfallSurfaceView) this.f11035D.f11892k;
        bVar.f620p = this;
        bVar.f621q = this;
    }

    /** Original onResume: restore decoding (message type/font/background/record start/waterfall) */
    public void onResume() {
        android.content.SharedPreferences prefs = mActivity.getSharedPreferences(
            mActivity.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        String messageType = prefs.getString("message_type", "general_text");
        b bVar = this.f11037F;
        if (bVar != null) {
            bVar.f623s.set(messageType.equals("ham_radio_qso"));
        }
        ((DecodedTextView) this.f11035D.f11888g).setTextSize(Math.max(7, prefs.getInt("text_font_size", 18)));
        b bVar2 = this.f11037F;
        if (bVar2 != null) {
            bVar2.f617m.b(mActivity);
            ((DecodedTextView) this.f11035D.f11888g).setBackgroundColor(this.f11037F.f617m.f665a[0]);
        }
        a aVar = this.f11036E;
        if (aVar != null) {
            android.media.AudioRecord audioRecord = aVar.f596g;
            audioRecord.startRecording();
            if (audioRecord.getState() != 1) {
                android.util.Log.e("AudioRecord", "cannot start");
            }
            aVar.f598i.set(true);
        }
        ((WaterfallSurfaceView) this.f11035D.f11892k).onResume();
        // Originally: ad lifecycle (removed)
    }

    /** Original onPause: stop recording + waterfall pause */
    public void onPause() {
        a aVar = this.f11036E;
        if (aVar != null) {
            aVar.f598i.set(false);
            android.media.AudioRecord audioRecord = aVar.f596g;
            audioRecord.stop();
            if (audioRecord.getState() != 1) {
                android.util.Log.e("AudioRecord", "cannot stop");
            }
        }
        ((WaterfallSurfaceView) this.f11035D.f11892k).onPause();
    }

    /** Original onDestroy: release audio/decoder (billing cleanup removed) */
    public void onDestroy() {
        a aVar = this.f11036E;
        if (aVar != null) {
            aVar.f598i.set(false);
            try {
                aVar.f596g.stop();
                aVar.f596g.release();
            } catch (Exception ignored) {
            }
            aVar.f597h.shutdownNow();
        }
        this.f11036E = null;
        this.f11037F = null;
    }

    /** Menu: clear decoded text (original clear_mnu) */
    public void clearDecoded() {
        ((DecodedTextView) this.f11035D.f11888g).setText("");
    }

    /** Menu: pause/resume decoding (original pause_mnu, red/white icon toggle logic) */
    public boolean togglePause() {
        java.util.concurrent.atomic.AtomicBoolean atomicBoolean = ((DecodedTextView) this.f11035D.f11888g).f11033o;
        atomicBoolean.set(!atomicBoolean.get());
        return atomicBoolean.get();
    }

    /** Menu: save text (original save_mnu -> DecodedTextView.r()) */
    public void saveText() {
        ((DecodedTextView) this.f11035D.f11888g).r();
    }

    /** Menu: record signals (original record_signals_mnu) */
    public void recordSignals() {
        this.f11037F.f626v.set(true);
    }

    /** Original tap_back_again_to_close hint */
    public boolean handleBackPress() {
        if (System.currentTimeMillis() < this.f11038G + 2000) {
            return true; // double-back: exit
        }
        this.f11038G = System.currentTimeMillis();
        Toast.makeText(mActivity, R.string.tap_back_again_to_close, 0).show();
        return false;
    }

    /** Start decoding after permission granted (original onRequestPermissionsResult success) */
    public void onPermissionGranted() {
        v();
    }
}
