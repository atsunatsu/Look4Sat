package com.ve3nea.morse_expert;

import F2.a;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ActionMode;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.PrintWriter;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 照搬自 Morse Expert 1.15 DecodedTextView, 类名保持混淆原名。
 * 适配: 原 extends l.C1702e0(appcompat 混淆类) -> 直接 extends TextView
 * (C1702e0 仅提供 TextView 兼容功能, 本类未使用其特有方法)。
 */
public class DecodedTextView extends TextView {

    /* renamed from: m, reason: collision with root package name */
    public final Context f11031m;

    /* renamed from: n, reason: collision with root package name */
    public boolean f11032n;

    /* renamed from: o, reason: collision with root package name */
    public final AtomicBoolean f11033o;

    public DecodedTextView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.f11032n = false;
        this.f11033o = new AtomicBoolean(false);
        this.f11031m = context;
        setCustomSelectionActionModeCallback(getActionModeCallback());
    }

    private ActionMode.Callback getActionModeCallback() {
        return new a(this);
    }

    private String getDecodedText() {
        int selectionStart = getSelectionStart();
        int selectionEnd = getSelectionEnd();
        int max = Math.max(0, Math.min(selectionStart, selectionEnd));
        int max2 = Math.max(0, Math.max(selectionStart, selectionEnd));
        if (max2 > max) {
            return getText().subSequence(max, max2).toString();
        }
        return getText().toString();
    }

    @Override // android.widget.TextView
    public final void onSelectionChanged(int i4, int i5) {
        super.onSelectionChanged(i4, i5);
        if (hasSelection()) {
            this.f11032n = true;
        }
    }

    public final void q(SpannableStringBuilder spannableStringBuilder) {
        boolean z3;
        try {
            CharSequence text = getText();
            Layout layout = getLayout();
            int lineCount = getLineCount();
            int length = text.length();
            int lineHeight = getLineHeight();
            int scrollY = getScrollY();
            if (layout.getLineBottom(lineCount - 1) == getHeight() + scrollY) {
                z3 = true;
            } else {
                z3 = false;
            }
            getSelectionStart();
            getSelectionEnd();
            hasSelection();
            if (z3) {
                Selection.setSelection((Spannable) text, length, length);
            }
            append(spannableStringBuilder);
            int i4 = lineCount - 50;
            if (i4 > 0) {
                int i5 = lineHeight * i4;
                getEditableText().delete(0, layout.getLineEnd(lineCount - 51));
                if (scrollY >= i5) {
                    scrollBy(0, -i5);
                }
            }
        } catch (Exception e4) {
            Log.e("printChars()", "Exception: ", e4);
        }
    }

    public final void r() {
        String decodedText = getDecodedText();
        boolean isEmpty = TextUtils.isEmpty(decodedText);
        Context context = this.f11031m;
        if (isEmpty) {
            Toast.makeText(context, "No text to save", 0).show();
            return;
        }
        try {
            File file = new File(getContext().getExternalFilesDir(null), ((String) DateFormat.format("yyyy-MM-dd_HH_mm_ss", new Date())) + ".txt");
            String absolutePath = file.getAbsolutePath();
            PrintWriter printWriter = new PrintWriter(file);
            try {
                printWriter.println(decodedText);
                printWriter.close();
                MediaScannerConnection.scanFile(context, new String[]{absolutePath}, new String[]{"text/plain"}, null);
                Toast.makeText(context, "Text saved to " + absolutePath, 1).show();
            } catch (Throwable th) {
                printWriter.close();
                throw th;
            }
        } catch (Exception e4) {
            Log.e("File Save", "Failed", e4);
            Toast.makeText(context, "Unable to save: " + e4.getMessage(), 1).show();
        }
    }

    public final void s() {
        Intent intent = new Intent("android.intent.action.SEND");
        intent.setFlags(268435456);
        intent.putExtra("android.intent.extra.TEXT", getDecodedText());
        intent.setType("text/plain");
        this.f11031m.startActivity(Intent.createChooser(intent, "Share Decoded Text"));
    }
}
