package com.ReactNativeBlobUtil;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import com.ReactNativeBlobUtil.Utils.FileDescription;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.network.CookieJarContainer;
import com.facebook.react.modules.network.ForwardingCookieHandler;
import com.facebook.react.modules.network.OkHttpClientProvider;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import okhttp3.JavaNetCookieJar;
import okhttp3.OkHttpClient;

import static android.app.Activity.RESULT_OK;
import static com.ReactNativeBlobUtil.ReactNativeBlobUtilConst.GET_CONTENT_INTENT;

public class ReactNativeBlobUtil extends ReactContextBaseJavaModule {

    private final OkHttpClient mClient;

    static ReactApplicationContext RCTContext;
    private static final LinkedBlockingQueue<Runnable> taskQueue = new LinkedBlockingQueue<>();
    private static final ThreadPoolExecutor threadPool = new ThreadPoolExecutor(5, 10, 5000, TimeUnit.MILLISECONDS, taskQueue);
    static LinkedBlockingQueue<Runnable> fsTaskQueue = new LinkedBlockingQueue<>();
    private static final ThreadPoolExecutor fsThreadPool = new ThreadPoolExecutor(2, 10, 5000, TimeUnit.MILLISECONDS, taskQueue);
    private static boolean ActionViewVisible = false;
    private static final SparseArray<Promise> promiseTable = new SparseArray<>();

    public ReactNativeBlobUtil(ReactApplicationContext reactContext) {

        super(reactContext);

        mClient = OkHttpClientProvider.getOkHttpClient();
        ForwardingCookieHandler mCookieHandler = new ForwardingCookieHandler(reactContext);
        CookieJarContainer mCookieJarContainer = (CookieJarContainer) mClient.cookieJar();
        mCookieJarContainer.setCookieJar(new JavaNetCookieJar(mCookieHandler));

        RCTContext = reactContext;
        reactContext.addActivityEventListener(new ActivityEventListener() {
            @Override
            public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
                if (requestCode == GET_CONTENT_INTENT && resultCode == RESULT_OK) {
                    Uri d = data.getData();
                    promiseTable.get(GET_CONTENT_INTENT).resolve(d.toString());
                    promiseTable.remove(GET_CONTENT_INTENT);
                }
            }

            @Override
            public void onNewIntent(Intent intent) {

            }
        });
    }

    @NonNull
    @Override
    public String getName() {
        return "ReactNativeBlobUtil";
    }

    @Override
    public Map<String, Object> getConstants() {
        Map<String, Object> res = new HashMap<>();
        res.putAll(ReactNativeBlobUtilFS.getSystemfolders(this.getReactApplicationContext()));
        res.putAll(ReactNativeBlobUtilFS.getLegacySystemfolders(this.getReactApplicationContext()));
        System.out.println(res);
        return res;
    }

    @ReactMethod
    public void createFile(final String path, final String content, final String encode, final Promise promise) {
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                ReactNativeBlobUtilFS.createFile(path, content, encode, promise);
            }
        });
    }

    @ReactMethod
    public void createFileASCII(final String path, final ReadableArray dataArray, final Promise promise) {
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                ReactNativeBlobUtilFS.createFileASCII(path, dataArray, promise);
            }
        });
    }

    @ReactMethod
    public void actionViewIntent(String path, String mime, @Nullable String chooserTitle, final Promise promise) {
        try {
            Uri uriForFile = null;
            if (!ReactNativeBlobUtilUtils.isContentUri(path)) {
                uriForFile = FileProvider.getUriForFile(this.getReactApplicationContext(),
                        this.getReactApplicationContext().getPackageName() + ".provider", new File(path));
            } else {
                uriForFile = Uri.parse(path);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Create the intent with data and type
                intent.setDataAndType(uriForFile, mime);

                // Set flag to give temporary permission to external app to use FileProvider
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                // All the activity to be opened outside of an activity
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            } else {
                intent.setDataAndType(Uri.parse("file://" + path), mime).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }

            if (chooserTitle != null) {
                intent = Intent.createChooser(intent, chooserTitle);
            }

            try {
                this.getReactApplicationContext().startActivity(intent);
                promise.resolve(true);
            } catch (ActivityNotFoundException ex) {
                promise.reject("ENOAPP", "No app installed for " + mime);
            }

            ActionViewVisible = true;

            final LifecycleEventListener listener = new LifecycleEventListener() {

                @Override
                public void onHostResume() {
                    if (ActionViewVisible)
                        promise.resolve(null);
                    RCTContext.removeLifecycleEventListener(this);
                }

                @Override
                public void onHostPause() {

                }

                @Override
                public void onHostDestroy() {

                }
            };
            RCTContext.addLifecycleEventListener(listener);
        } catch (Exception ex) {
            promise.reject("EUNSPECIFIED", ex.getLocalizedMessage());
        }
    }

    @ReactMethod
    public void writeArrayChunk(final String streamId, final ReadableArray dataArray, final Callback callback) {
        ReactNativeBlobUtilStream.writeArrayChunk(streamId, dataArray, callback);
    }

    @ReactMethod
    public void unlink(String path, Callback callback) {
        ReactNativeBlobUtilFS.unlink(path, callback);
    }

    @ReactMethod
    public void mkdir(String path, Promise promise) {
        ReactNativeBlobUtilFS.mkdir(path, promise);
    }

    @ReactMethod
    public void exists(String path, Callback callback) {
        ReactNativeBlobUtilFS.exists(path, callback);
    }

    @ReactMethod
    public void cp(final String path, final String dest, final Callback callback) {
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                ReactNativeBlobUtilFS.cp(path, dest, callback);
            }
        });
    }

    @ReactMethod
    public void mv(String path, String dest, Callback callback) {
        ReactNativeBlobUtilFS.mv(path, dest, callback);
    }

    @ReactMethod
    public void ls(String path, Promise promise) {
        ReactNativeBlobUtilFS.ls(path, promise);
    }

    @ReactMethod
    public void writeStream(String path, String encode, boolean append, Callback callback) {
        new ReactNativeBlobUtilStream(this.getReactApplicationContext()).writeStream(path, encode, append, callback);
    }

    @ReactMethod
    public void writeChunk(String streamId, String data, Callback callback) {
        ReactNativeBlobUtilStream.writeChunk(streamId, data, callback);
    }

    @ReactMethod
    public void closeStream(String streamId, Callback callback) {
        ReactNativeBlobUtilStream.closeStream(streamId, callback);
    }

    @ReactMethod
    public void removeSession(ReadableArray paths, Callback callback) {
        ReactNativeBlobUtilFS.removeSession(paths, callback);
    }

    @ReactMethod
    public void readFile(final String path, final String encoding, final boolean transformFile, final Promise promise) {
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                ReactNativeBlobUtilFS.readFile(path, encoding, transformFile, promise);
            }
        });
    }

    @ReactMethod
    public void writeFileArray(final String path, final ReadableArray data, final boolean append, final Promise promise) {
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                ReactNativeBlobUtilFS.writeFile(path, data, append, promise);
            }
        });
    }

    @ReactMethod
    public void writeFile(final String path, final String encoding, final String data, final boolean transformFile, final boolean append, final Promise promise) {
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                ReactNativeBlobUtilFS.writeFile(path, encoding, data, transformFile, append, promise);
            }
        });
    }

    @ReactMethod
    public void lstat(String path, Callback callback) {
        ReactNativeBlobUtilFS.lstat(path, callback);
    }

    @ReactMethod
    public void stat(String path, Callback callback) {
        ReactNativeBlobUtilFS.stat(path, callback);
    }

    @ReactMethod
    public void scanFile(final ReadableArray pairs, final Callback callback) {
        final ReactApplicationContext ctx = this.getReactApplicationContext();
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                int size = pairs.size();
                String[] p = new String[size];
                String[] m = new String[size];
                for (int i = 0; i < size; i++) {
                    ReadableMap pair = pairs.getMap(i);
                    if (pair.hasKey("path")) {
                        p[i] = pair.getString("path");
                        if (pair.hasKey("mime"))
                            m[i] = pair.getString("mime");
                        else
                            m[i] = null;
                    }
                }
                new ReactNativeBlobUtilFS(ctx).scanFile(p, m, callback);
            }
        });
    }

    @ReactMethod
    public void hash(final String path, final String algorithm, final Promise promise) {
        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                ReactNativeBlobUtilFS.hash(path, algorithm, promise);
            }
        });
    }

    /**
     * @param path       Stream file path
     * @param encoding   Stream encoding, should be one of `base64`, `ascii`, and `utf8`
     * @param bufferSize Stream buffer size, default to 4096 or 4095(base64).
     */
    @ReactMethod
    public void readStream(final String path, final String encoding, final int bufferSize, final int tick, final String streamId) {
        final ReactApplicationContext ctx = this.getReactApplicationContext();
        fsThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                ReactNativeBlobUtilStream fs = new ReactNativeBlobUtilStream(ctx);
                fs.readStream(path, encoding, bufferSize, tick, streamId);
            }
        });
    }

    @ReactMethod
    public void cancelRequest(String taskId, Callback callback) {
        try {
            ReactNativeBlobUtilReq.cancelTask(taskId);
            callback.invoke(null, taskId);
        } catch (Exception ex) {
            callback.invoke(ex.getLocalizedMessage(), null);
        }
    }

    @ReactMethod
    public void slice(String src, String dest, int start, int end, Promise promise) {
        ReactNativeBlobUtilFS.slice(src, dest, start, end, "", promise);
    }

    @ReactMethod
    public void enableProgressReport(String taskId, int interval, int count) {
        ReactNativeBlobUtilProgressConfig config = new ReactNativeBlobUtilProgressConfig(true, interval, count, ReactNativeBlobUtilProgressConfig.ReportType.Download);
        ReactNativeBlobUtilReq.progressReport.put(taskId, config);
    }

    @ReactMethod
    public void df(final Callback callback) {
        fsThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                ReactNativeBlobUtilFS.df(callback, getReactApplicationContext());
            }
        });
    }


    @ReactMethod
    public void enableUploadProgressReport(String taskId, int interval, int count) {
        ReactNativeBlobUtilProgressConfig config = new ReactNativeBlobUtilProgressConfig(true, interval, count, ReactNativeBlobUtilProgressConfig.ReportType.Upload);
        ReactNativeBlobUtilReq.uploadProgressReport.put(taskId, config);
    }

    @ReactMethod
    public void fetchBlob(ReadableMap options, String taskId, String method, String url, ReadableMap headers, String body, final Callback callback) {
        new ReactNativeBlobUtilReq(options, taskId, method, url, headers, body, null, mClient, callback).run();
    }

    @ReactMethod
    public void fetchBlobForm(ReadableMap options, String taskId, String method, String url, ReadableMap headers, ReadableArray body, final Callback callback) {
        new ReactNativeBlobUtilReq(options, taskId, method, url, headers, null, body, mClient, callback).run();
    }

    @ReactMethod
    public void getContentIntent(String mime, Promise promise) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        if (mime != null)
            i.setType(mime);
        else
            i.setType("*/*");
        promiseTable.put(GET_CONTENT_INTENT, promise);
        this.getReactApplicationContext().startActivityForResult(i, GET_CONTENT_INTENT, null);

    }

    @ReactMethod
    public void addCompleteDownload(ReadableMap config, Promise promise) {
        DownloadManager dm = (DownloadManager) RCTContext.getSystemService(RCTContext.DOWNLOAD_SERVICE);
        if (config == null || !config.hasKey("path")) {
            promise.reject("EINVAL", "ReactNativeBlobUtil.addCompleteDownload config or path missing.");
            return;
        }
        String path = ReactNativeBlobUtilUtils.normalizePath(config.getString("path"));
        if (path == null) {
            promise.reject("EINVAL", "ReactNativeBlobUtil.addCompleteDownload can not resolve URI:" + config.getString("path"));
            return;
        }
        try {
            WritableMap stat = ReactNativeBlobUtilFS.statFile(path);
            dm.addCompletedDownload(
                    config.hasKey("title") ? config.getString("title") : "",
                    config.hasKey("description") ? config.getString("description") : "",
                    true,
                    config.hasKey("mime") ? config.getString("mime") : null,
                    path,
                    Long.valueOf(stat.getString("size")),
                    config.hasKey("showNotification") && config.getBoolean("showNotification")
            );
            promise.resolve(null);
        } catch (Exception ex) {
            promise.reject("EUNSPECIFIED", ex.getLocalizedMessage());
        }

    }

    @ReactMethod
    public void getSDCardDir(Promise promise) {
        ReactNativeBlobUtilFS.getSDCardDir(this.getReactApplicationContext(), promise);
    }

    @ReactMethod
    public void getSDCardApplicationDir(Promise promise) {
        ReactNativeBlobUtilFS.getSDCardApplicationDir(this.getReactApplicationContext(), promise);
    }

    @ReactMethod
    public void createMediaFile(ReadableMap filedata, String mt, Promise promise) {
        if (!(filedata.hasKey("name") && filedata.hasKey("parentFolder") && filedata.hasKey("mimeType"))) {
            promise.reject("ReactNativeBlobUtil.createMediaFile", "invalid filedata: " + filedata.toString());
            return;
        }
        if (mt == null) promise.reject("ReactNativeBlobUtil.createMediaFile", "invalid mediatype");

        FileDescription file = new FileDescription(filedata.getString("name"), filedata.getString("mimeType"), filedata.getString("parentFolder"));
        Uri res = ReactNativeBlobUtilMediaCollection.createNewMediaFile(file, ReactNativeBlobUtilMediaCollection.MediaType.valueOf(mt), this.getReactApplicationContext());
        if (res != null) promise.resolve(res.toString());
        else promise.reject("ReactNativeBlobUtil.createMediaFile", "File could not be created");
    }

    @ReactMethod
    public void writeToMediaFile(String fileUri, String path, boolean transformFile, Promise promise) {
        boolean res = ReactNativeBlobUtilMediaCollection.writeToMediaFile(Uri.parse(fileUri), path, transformFile, promise);
        if (res) promise.resolve("Success");
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @ReactMethod
    public void copyToInternal(String contentUri, String destpath, Promise promise) {
        ReactNativeBlobUtilMediaCollection.copyToInternal(Uri.parse(contentUri), destpath, promise);
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    @ReactMethod
    public void getBlob(String contentUri, String encoding, Promise promise) {
        ReactNativeBlobUtilMediaCollection.getBlob(Uri.parse(contentUri), encoding, promise);
    }

    @ReactMethod
    public void copyToMediaStore(ReadableMap filedata, String mt, String path, Promise promise) {
        if (!(filedata.hasKey("name") && filedata.hasKey("parentFolder") && filedata.hasKey("mimeType"))) {
            promise.reject("ReactNativeBlobUtil.createMediaFile", "invalid filedata: " + filedata.toString());
            return;
        }
        if (mt == null) {
            promise.reject("ReactNativeBlobUtil.createMediaFile", "invalid mediatype");
            return;
        }
        if (path == null) {
            promise.reject("ReactNativeBlobUtil.createMediaFile", "invalid path");
            return;
        }

        FileDescription file = new FileDescription(filedata.getString("name"), filedata.getString("mimeType"), filedata.getString("parentFolder"));
        Uri fileuri = ReactNativeBlobUtilMediaCollection.createNewMediaFile(file, ReactNativeBlobUtilMediaCollection.MediaType.valueOf(mt), this.getReactApplicationContext());

        if (fileuri == null) {
            promise.reject("ReactNativeBlobUtil.createMediaFile", "File could not be created");
            return;
        }

        boolean res = ReactNativeBlobUtilMediaCollection.writeToMediaFile(fileuri, path, false, promise);
        if (res) promise.resolve(fileuri.toString());
    }

}
