package co.hernan.lectorpdf;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String HOST = "app.local";
    private static final int PICK = 41;

    private WebView web;
    private volatile Uri current;
    private String currentName = "documento.pdf";
    private long currentSize = -1;
    private boolean pageReady = false;
    private boolean full = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#1f2228"));
        w.setNavigationBarColor(Color.parseColor("#1f2228"));

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                return serve(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                if (current != null) pushToPage();
            }
        });

        handle(getIntent());
        web.loadUrl("https://" + HOST + "/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri != null) setDocument(uri);
    }

    private void setDocument(Uri uri) {
        current = uri;
        currentName = "documento.pdf";
        currentSize = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int z = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !c.isNull(n)) currentName = c.getString(n);
                if (z >= 0 && !c.isNull(z)) currentSize = c.getLong(z);
            }
        } catch (Exception e) {
            String last = uri.getLastPathSegment();
            if (last != null) currentName = last;
        }
        if (pageReady) pushToPage();
    }

    private void pushToPage() {
        String js = "window.openFromApp && window.openFromApp(" + JSONObject.quote(currentName) + "," + currentSize + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private WebResourceResponse serve(Uri url) {
        if (!HOST.equals(url.getHost())) return null;
        String path = url.getPath() == null ? "/" : url.getPath();
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        try {
            if (path.equals("/doc.pdf")) {
                Uri u = current;
                if (u == null) return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new java.io.ByteArrayInputStream(new byte[0]));
                InputStream in = getContentResolver().openInputStream(u);
                return new WebResourceResponse("application/pdf", null, 200, "OK", h, in);
            }
            String file = path.startsWith("/") ? path.substring(1) : path;
            if (file.isEmpty()) file = "index.html";
            String mime = file.endsWith(".html") ? "text/html" : file.endsWith(".js") ? "application/javascript" : "application/octet-stream";
            return new WebResourceResponse(mime, "utf-8", 200, "OK", h, getAssets().open(file));
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", h, new java.io.ByteArrayInputStream(new byte[0]));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK && res == RESULT_OK && data != null && data.getData() != null) {
            Uri u = data.getData();
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            setDocument(u);
        }
    }

    @Override
    public void onBackPressed() {
        if (full) { setFull(false); return; }
        super.onBackPressed();
    }

    @SuppressWarnings("deprecation")
    private void setFull(boolean on) {
        full = on;
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            d.setSystemUiVisibility(0);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void pick() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/pdf");
                try { startActivityForResult(i, PICK); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "No hay selector de archivos", Toast.LENGTH_SHORT).show(); }
            });
        }

        @JavascriptInterface
        public void toggleFull() {
            runOnUiThread(() -> setFull(!full));
        }

        @JavascriptInterface
        public boolean isNight() {
            int m = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return m == Configuration.UI_MODE_NIGHT_YES;
        }

        @JavascriptInterface
        public void share() {
            Uri u = current;
            if (u == null) return;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("application/pdf");
                i.putExtra(Intent.EXTRA_STREAM, u);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(i, "Compartir PDF")); } catch (Exception ignored) {}
            });
        }
    }
}
