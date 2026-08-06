package F2;

import com.rtbishop.look4sat.feature.cw.R;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import com.ve3nea.morse_expert.DecodedTextView;

/* loaded from: classes.dex */
public final class a implements ActionMode.Callback {

    /* renamed from: a, reason: collision with root package name */
    public final /* synthetic */ DecodedTextView f564a;

    public a(DecodedTextView decodedTextView) {
        this.f564a = decodedTextView;
    }

    @Override // android.view.ActionMode.Callback
    public final boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        DecodedTextView decodedTextView = this.f564a;
        if (itemId != 73) {
            if (itemId != 74) {
                return false;
            }
            decodedTextView.s();
            return true;
        }
        decodedTextView.r();
        actionMode.finish();
        return true;
    }

    @Override // android.view.ActionMode.Callback
    public final boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
        this.f564a.f11032n = true;
        // 原: if (menu.findItem(R.id.shareText) == null) 检查(shareText 为原 APK 资源, 简化后始终添加)
        menu.add(0, 74, 0, "Share").setIcon(R.drawable.ic_baseline_share_24);
        menu.add(0, 73, 0, "Save").setIcon(R.drawable.ic_baseline_save_24);
        return true;
    }

    @Override // android.view.ActionMode.Callback
    public final void onDestroyActionMode(ActionMode actionMode) {
        this.f564a.f11032n = false;
    }

    @Override // android.view.ActionMode.Callback
    public final boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
        return false;
    }
}
