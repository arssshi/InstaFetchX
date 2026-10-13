package com.instafetchx;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** The whole app UI: paste a link, download, see recent saves. */
public class MainActivity extends Activity implements View.OnClickListener, Bus.Listener {

    private EditText input;
    private TextView status;
    private TextView btn;
    private TextView hint;
    private View row;
    private ProgressBar bar;
    private ProgressBar spin;
    private ImageButton clear;
    private ListView list;
    private TextView empty;
    private ArrayAdapter<History.Entry> adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        input = findViewById(R.id.input);
        btn = findViewById(R.id.btn);
        hint = findViewById(R.id.hint);
        row = findViewById(R.id.row);
        bar = findViewById(R.id.bar);
        spin = findViewById(R.id.spin);
        clear = findViewById(R.id.clear);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        status = findViewById(R.id.status);
        ImageButton menu = findViewById(R.id.menu);

        btn.setOnClickListener(this);
        clear.setOnClickListener(this);
        menu.setOnClickListener(this);
        list.setEmptyView(empty);
        list.setOnItemClickListener((AdapterView<?> p, View v, int pos, long id) -> {
            History.Entry e = adapter.getItem(pos);
            if (e != null) Util.openFile(this, e.uri);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            History.Entry e = adapter.getItem(pos);
            if (e != null) {
                History.remove(this, e.id);
                refreshHistory();
                Toast.makeText(this, "Removed from history", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                clear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        input.setOnEditorActionListener((TextView v, int actionId, KeyEvent event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                onClick(btn);
                return true;
            }
            return false;
        });
        pressAnim(btn);
        requestPermissionsIfNeeded();
    }

    private void pressAnim(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                    break;
            }
            return false;
        });
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        } else if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 43);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bus.set(this);
        if (adapter == null) {
            adapter = new Adapter(this);
            list.setAdapter(adapter);
        }
        refreshHistory();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Bus.set(null);
    }

    private void refreshHistory() {
        List<History.Entry> entries = History.load(this);
        adapter.clear();
        adapter.addAll(entries);
        findViewById(R.id.clearAll).setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.clearAll)).setOnClickListener(v -> {
            History.clear(MainActivity.this);
            refreshHistory();
        });
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn) {
            startDownload();
        } else if (id == R.id.clear) {
            input.setText("");
        } else if (id == R.id.menu) {
            showMenu(v);
        }
    }

    private void startDownload() {
        String url = Res.extractUrl(input.getText().toString());
        if (url == null) {
            hint.setText("Paste a valid Instagram link first");
            hint.setTextColor(0xFFE14B5A);
            return;
        }
        hideKeyboard();
        working("Resolving link…");
        DownloadService.start(this, url);
    }

    private void working(String text) {
        hint.setVisibility(View.GONE);
        row.setVisibility(View.VISIBLE);
        spin.setVisibility(View.VISIBLE);
        bar.setVisibility(View.GONE);
        status.setText(text);
        status.setTextColor(getResources().getColor(R.color.text_secondary));
        btn.setText("Downloading…");
        btn.setEnabled(false);
    }

    private void idle(String text, int color) {
        row.setVisibility(View.VISIBLE);
        spin.setVisibility(View.GONE);
        bar.setVisibility(View.GONE);
        status.setText(text);
        status.setTextColor(color);
        btn.setText("Download");
        btn.setEnabled(true);
    }

    private void showMenu(View anchor) {
        PopupMenu p = new PopupMenu(this, anchor);
        p.getMenu().add(0, 1, 0, "Paste from clipboard");
        p.getMenu().add(0, 2, 1, "Session ID for stories…");
        p.getMenu().add(0, 3, 2, "About InstaFetchX");
        p.setOnMenuItemClickListener((MenuItem item) -> {
            int id = item.getItemId();
            if (id == 1) {
                pasteFromClipboard();
            } else if (id == 2) {
                sessionDialog();
            } else if (id == 3) {
                aboutDialog();
            }
            return true;
        });
        p.show();
    }

    private void pasteFromClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            CharSequence cs = cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0
                    ? cm.getPrimaryClip().getItemAt(0).getText() : null;
            String url = Res.extractUrl(cs == null ? null : cs.toString());
            if (url == null) {
                Toast.makeText(this, "Clipboard has no Instagram link", Toast.LENGTH_SHORT).show();
                return;
            }
            input.setText(url);
            input.setSelection(url.length());
            startDownload();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't read clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void sessionDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText et = new EditText(this);
        et.setSingleLine();
        et.setHint("sessionid cookie value");
        et.setText(Prefs.session(this) == null ? "" : Prefs.session(this));
        box.addView(et);
        TextView note = new TextView(this);
        note.setText("Stories & highlights need a logged-in session id. It's stored only on this device and sent only to instagram.com. Leave empty to disable.");
        note.setTextSize(12);
        note.setTextColor(getResources().getColor(R.color.text_secondary));
        box.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("Session ID")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Prefs.setSession(this, et.getText().toString());
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void aboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("InstaFetchX 1.0")
                .setMessage("A tiny, open-source Instagram downloader.\n\n"
                        + "• Reels, posts, carousels, profile pictures\n"
                        + "• Stories & highlights (with session id)\n"
                        + "• Highest quality Instagram serves\n"
                        + "• No accounts, no ads, no trackers\n\n"
                        + "Please download only content you have the right to.")
                .setPositiveButton("Close", null)
                .show();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) { }
    }

    // ---- Bus callbacks --------------------------------------------------------

    @Override
    public void onStart(int total) {
        working("Downloading " + (total > 1 ? total + " files" : "1 file") + "…");
        if (total > 1) {
            bar.setVisibility(View.VISIBLE);
            bar.setMax(total);
            bar.setProgress(0);
        }
    }

    @Override
    public void onProgress(int done, int total, String name, int percent) {
        status.setText(done + "/" + total + "  ·  " + name + "  " + percent + "%");
        if (total > 1) bar.setProgress(done);
        else {
            spin.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onFile(String uri, String name) {
        refreshHistory();
    }

    @Override
    public void onDone(int count, boolean error) {
        if (count > 0) {
            idle("Saved " + count + (count == 1 ? " file" : " files") + " ✓", getColor(R.color.ok));
            input.setText("");
        } else {
            idle(error ? "Download failed" : "Nothing saved", getColor(R.color.err));
        }
    }

    @Override
    public void onError(String message) {
        hint.setVisibility(View.GONE);
        row.setVisibility(View.VISIBLE);
        status.setText(message);
        status.setTextColor(getColor(R.color.err));
        btn.setText("Download");
        btn.setEnabled(true);
    }

    // ---- history adapter --------------------------------------------------------

    private final class Adapter extends ArrayAdapter<History.Entry> {
        Adapter(Context c) {
            super(c, R.layout.item_history);
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_history, parent, false);
            }
            History.Entry e = getItem(position);
            ImageView icon = v.findViewById(R.id.icon);
            TextView name = v.findViewById(R.id.name);
            TextView meta = v.findViewById(R.id.meta);
            name.setText(e.name);
            meta.setText(e.sub());
            icon.setImageResource(R.drawable.ic_file);
            icon.setColorFilter(e.video ? getColor(R.color.accent) : getColor(R.color.accent2));
            return v;
        }
    }
}
