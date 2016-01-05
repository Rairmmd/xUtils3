package org.xutils.http;

import android.text.TextUtils;

import org.xutils.common.Callback;
import org.xutils.common.task.AbsTask;
import org.xutils.common.task.Priority;
import org.xutils.common.task.PriorityExecutor;
import org.xutils.common.util.IOUtil;
import org.xutils.common.util.LogUtil;
import org.xutils.common.util.ParameterizedTypeUtil;
import org.xutils.ex.HttpException;
import org.xutils.ex.HttpRedirectException;
import org.xutils.http.app.HttpRetryHandler;
import org.xutils.http.app.InterceptRequestListener;
import org.xutils.http.app.RedirectHandler;
import org.xutils.http.app.RequestTracker;
import org.xutils.http.request.UriRequest;
import org.xutils.http.request.UriRequestFactory;
import org.xutils.x;

import java.io.Closeable;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by wyouflf on 15/7/23.
 * http 请求任务
 */
public class HttpTask<ResultType> extends AbsTask<ResultType> implements ProgressHandler {

    // 请求相关
    private RequestParams params;
    private UriRequest request;
    private RequestWorker requestWorker;
    private final Executor executor;
    private final Callback.CommonCallback<ResultType> callback;

    // 缓存控制
    private Object rawResult = null;
    private final Object cacheLock = new Object();
    private volatile Boolean trustCache = null;

    // 扩展callback
    private Callback.CacheCallback<ResultType> cacheCallback;
    private Callback.PrepareCallback prepareCallback;
    private Callback.ProgressCallback progressCallback;
    private InterceptRequestListener interceptRequestListener;

    // 日志追踪
    private RequestTracker tracker;

    // 文件下载线程数限制
    private Type loadType;
    private final static int MAX_FILE_LOAD_WORKER = 3;
    private final static AtomicInteger sCurrFileLoadCount = new AtomicInteger(0);

    // 文件下载任务
    private static final HashMap<String, WeakReference<HttpTask<?>>>
            DOWNLOAD_TASK = new HashMap<String, WeakReference<HttpTask<?>>>(1);

    private static final PriorityExecutor HTTP_EXECUTOR = new PriorityExecutor(5, true);
    private static final PriorityExecutor CACHE_EXECUTOR = new PriorityExecutor(5, true);


    public HttpTask(RequestParams params, Callback.Cancelable cancelHandler,
                    Callback.CommonCallback<ResultType> callback) {
        super(cancelHandler);

        assert params != null;
        assert callback != null;

        // set params & callback
        this.params = params;
        this.callback = callback;
        if (callback instanceof Callback.CacheCallback) {
            this.cacheCallback = (Callback.CacheCallback<ResultType>) callback;
        }
        if (callback instanceof Callback.PrepareCallback) {
            this.prepareCallback = (Callback.PrepareCallback) callback;
        }
        if (callback instanceof Callback.ProgressCallback) {
            this.progressCallback = (Callback.ProgressCallback<ResultType>) callback;
        }
        if (callback instanceof InterceptRequestListener) {
            this.interceptRequestListener = (InterceptRequestListener) callback;
        }

        {// init tracker
            RequestTracker customTracker = null;
            if (callback instanceof RequestTracker) {
                customTracker = (RequestTracker) callback;
            } else if (params instanceof RequestTracker) {
                customTracker = (RequestTracker) params;
            } else {
                customTracker = UriRequestFactory.getDefaultTracker();
            }
            if (customTracker != null) {
                tracker = new RequestTrackerWrapper(customTracker);
            }
        }

        {// init executor
            if (params.getExecutor() != null) {
                this.executor = params.getExecutor();
            } else {
                if (cacheCallback != null) {
                    this.executor = CACHE_EXECUTOR;
                } else {
                    this.executor = HTTP_EXECUTOR;
                }
            }
        }
    }

    // 解析loadType
    private void resolveLoadType() {
        Class<?> callBackType = callback.getClass();
        if (callback instanceof Callback.TypedCallback) {
            loadType = ((Callback.TypedCallback) callback).getResultType();
        } else if (callback instanceof Callback.PrepareCallback) {
            loadType = ParameterizedTypeUtil.getParameterizedType(callBackType, Callback.PrepareCallback.class, 0);
        } else {
            loadType = ParameterizedTypeUtil.getParameterizedType(callBackType, Callback.CommonCallback.class, 0);
        }
    }

    // 初始化请求参数
    private UriRequest createNewRequest() throws Throwable {
        // init request
        params.init();
        UriRequest result = UriRequestFactory.getUriRequest(params, loadType);
        result.setCallingClassLoader(callback.getClass().getClassLoader());
        result.setProgressHandler(this);
        return result;
    }

    // 文件下载冲突检测
    private void checkDownloadTask() {
        if (File.class == loadType) {
            synchronized (DOWNLOAD_TASK) {
                String downloadTaskKey = this.params.getSaveFilePath();
                /*{
                    // 不处理缓存文件下载冲突,
                    // 缓存文件下载冲突会抛出FileLockedException异常,
                    // 使用异常处理控制是否重新尝试下载.
                    if (TextUtils.isEmpty(downloadTaskKey)) {
                        downloadTaskKey = this.request.getCacheKey();
                    }
                }*/
                if (!TextUtils.isEmpty(downloadTaskKey)) {
                    WeakReference<HttpTask<?>> taskRef = DOWNLOAD_TASK.get(downloadTaskKey);
                    if (taskRef != null) {
                        HttpTask<?> task = taskRef.get();
                        if (task != null) {
                            task.cancel();
                            task.closeRequestSync();
                        }
                        DOWNLOAD_TASK.remove(downloadTaskKey);
                    }
                    DOWNLOAD_TASK.put(downloadTaskKey, new WeakReference<HttpTask<?>>(this));
                } // end if (!TextUtils.isEmpty(downloadTaskKey))
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ResultType doBackground() throws Throwable {

        if (this.isCancelled()) {
            throw new Callback.CancelledException("cancelled before request");
        }

        // 初始化请求参数
        ResultType result = null;
        resolveLoadType();
        request = createNewRequest();
        checkDownloadTask();
        // retry 初始化
        boolean retry = true;
        int retryCount = 0;
        Throwable exception = null;
        HttpRetryHandler retryHandler = this.params.getHttpRetryHandler();
        if (retryHandler == null) {
            retryHandler = new HttpRetryHandler();
        }
        retryHandler.setMaxRetryCount(this.params.getMaxRetryCount());

        if (this.isCancelled()) {
            throw new Callback.CancelledException("cancelled before request");
        }

        // 检查缓存
        Object cacheResult = null;
        if (cacheCallback != null && HttpMethod.permitsCache(params.getMethod())) {
            // 尝试从缓存获取结果, 并为请求头加入缓存控制参数.
            try {
                clearRawResult();
                LogUtil.d("load cache: " + this.request.getRequestUri());
                rawResult = this.request.loadResultFromCache();
            } catch (Throwable ex) {
                LogUtil.w("load disk cache error", ex);
            }

            if (this.isCancelled()) {
                clearRawResult();
                throw new Callback.CancelledException("cancelled before request");
            }

            if (rawResult != null) {
                if (prepareCallback != null) {
                    try {
                        cacheResult = prepareCallback.prepare(rawResult);
                    } catch (Throwable ex) {
                        cacheResult = null;
                        LogUtil.w("prepare disk cache error", ex);
                    } finally {
                        clearRawResult();
                    }
                } else {
                    cacheResult = rawResult;
                }

                if (this.isCancelled()) {
                    throw new Callback.CancelledException("cancelled before request");
                }

                if (cacheResult != null) {
                    // 同步等待是否信任缓存
                    this.update(FLAG_CACHE, cacheResult);
                    while (trustCache == null) {
                        synchronized (cacheLock) {
                            try {
                                cacheLock.wait();
                            } catch (Throwable ignored) {
                            }
                        }
                    }

                    // 处理完成
                    if (trustCache) {
                        return null;
                    }
                }
            }
        }

        if (trustCache == null) {
            trustCache = false;
        }

        if (cacheResult == null) {
            this.request.clearCacheHeader();
        }

        // 发起请求
        retry = true;
        while (retry) {
            retry = false;

            try {
                if (this.isCancelled()) {
                    throw new Callback.CancelledException("cancelled before request");
                }

                // 由loader发起请求, 拿到结果.
                this.request.close(); // retry 前关闭上次请求

                try {
                    clearRawResult();
                    // 开始请求工作
                    LogUtil.d("load: " + this.request.getRequestUri());
                    requestWorker = new RequestWorker();
                    if (params.isCancelFast()) {
                        requestWorker.start();
                        requestWorker.join();
                    } else {
                        requestWorker.run();
                    }
                    if (requestWorker.ex != null) {
                        throw requestWorker.ex;
                    }
                    rawResult = requestWorker.result;
                } catch (Throwable ex) {
                    clearRawResult();
                    if (this.isCancelled()) {
                        throw new Callback.CancelledException("cancelled during request");
                    } else {
                        throw ex;
                    }
                }

                if (prepareCallback != null) {

                    if (this.isCancelled()) {
                        throw new Callback.CancelledException("cancelled before request");
                    }

                    try {
                        result = (ResultType) prepareCallback.prepare(rawResult);
                    } finally {
                        clearRawResult();
                    }
                } else {
                    result = (ResultType) rawResult;
                }

                // 保存缓存
                if (cacheCallback != null && HttpMethod.permitsCache(params.getMethod())) {
                    this.request.save2Cache();
                }

                if (this.isCancelled()) {
                    throw new Callback.CancelledException("cancelled after request");
                }
            } catch (HttpRedirectException redirectEx) {
                retry = true;
                LogUtil.w("Http Redirect:" + params.getUri());
            } catch (Throwable ex) {
                if (this.request.getResponseCode() == 304) { // disk cache is valid.
                    return null;
                } else {
                    exception = ex;
                    if (this.isCancelled() && !(exception instanceof Callback.CancelledException)) {
                        exception = new Callback.CancelledException("canceled by user");
                    }
                    retry = retryHandler.retryRequest(ex, ++retryCount, this.request);
                }
            }

        }

        if (exception != null && result == null && !trustCache) {
            throw exception;
        }

        return result;
    }

    private static final int FLAG_CACHE = 1;
    private static final int FLAG_PROGRESS = 2;

    @Override
    @SuppressWarnings("unchecked")
    protected void onUpdate(int flag, Object... args) {
        switch (flag) {
            case FLAG_CACHE: {
                synchronized (cacheLock) {
                    try {
                        ResultType result = (ResultType) args[0];
                        if (tracker != null) {
                            tracker.onCache(request, result);
                        }
                        trustCache = this.cacheCallback.onCache(result);
                    } catch (Throwable ex) {
                        trustCache = false;
                        callback.onError(ex, true);
                    } finally {
                        cacheLock.notifyAll();
                    }
                }
            }
            case FLAG_PROGRESS: {
                if (this.progressCallback != null && args.length == 3) {
                    try {
                        this.progressCallback.onLoading(
                                ((Number) args[0]).longValue(),
                                ((Number) args[1]).longValue(),
                                (Boolean) args[2]);
                    } catch (Throwable ex) {
                        callback.onError(ex, true);
                    }
                }
            }
            default: {
                break;
            }
        }
    }

    @Override
    protected void onWaiting() {
        if (tracker != null) {
            tracker.onWaiting(request);
        }
        if (progressCallback != null) {
            progressCallback.onWaiting();
        }
    }

    @Override
    protected void onStarted() {
        if (tracker != null) {
            tracker.onStart(request);
        }
        if (progressCallback != null) {
            progressCallback.onStarted();
        }
    }

    @Override
    protected void onSuccess(ResultType result) {
        if (tracker != null) {
            tracker.onSuccess(request, result);
        }
        if (result != null) {
            callback.onSuccess(result);
        }
    }

    @Override
    protected void onError(Throwable ex, boolean isCallbackError) {
        if (tracker != null) {
            tracker.onError(request, ex, isCallbackError);
        }
        callback.onError(ex, isCallbackError);
    }


    @Override
    protected void onCancelled(Callback.CancelledException cex) {
        if (tracker != null) {
            tracker.onCancelled(request);
        }
        callback.onCancelled(cex);
    }

    @Override
    protected void onFinished() {
        if (tracker != null) {
            tracker.onFinished(request);
        }
        x.task().run(new Runnable() {
            @Override
            public void run() {
                closeRequestSync();
            }
        });
        callback.onFinished();
    }

    private void clearRawResult() {
        if (rawResult instanceof Closeable) {
            IOUtil.closeQuietly((Closeable) rawResult);
        }
        rawResult = null;
    }

    @Override
    protected void cancelWorks() {
        x.task().run(new Runnable() {
            @Override
            public void run() {
                closeRequestSync();
            }
        });
    }

    private void closeRequestSync() {
        clearRawResult();
        if (requestWorker != null && params.isCancelFast()) {
            try {
                requestWorker.interrupt();
            } catch (Throwable ignored) {
            }
        }
        // wtf: okhttp close the inputStream be locked by BufferedInputStream#read
        IOUtil.closeQuietly(request);
    }

    @Override
    public Executor getExecutor() {
        return this.executor;
    }

    @Override
    public Priority getPriority() {
        return params.getPriority();
    }

    // ############################### start: region implements ProgressHandler
    private final static long RATE = 300; // 0.3s
    private long lastUpdateTime;

    /**
     * @param total
     * @param current
     * @param forceUpdateUI
     * @return continue
     */
    @Override
    public boolean updateProgress(long total, long current, boolean forceUpdateUI) {

        if (isCancelled() || isFinished()) {
            return false;
        }

        if (progressCallback != null && request != null && total > 0) {
            if (total < current) {
                total = current;
            }
            if (forceUpdateUI) {
                lastUpdateTime = System.currentTimeMillis();
                this.update(FLAG_PROGRESS, total, current, request.isLoading());
            } else {
                long currTime = System.currentTimeMillis();
                if (currTime - lastUpdateTime >= RATE) {
                    lastUpdateTime = currTime;
                    this.update(FLAG_PROGRESS, total, current, request.isLoading());
                }
            }
        }

        return !isCancelled() && !isFinished();
    }

    // ############################### end: region implements ProgressHandler

    @Override
    public String toString() {
        return params.toString();
    }


    /**
     * 请求发送和加载数据线程.
     * 该线程被join到HttpTask的工作线程去执行.
     * 它的主要作用是为了能强行中断请求的链接过程;
     * 并辅助限制同时下载文件的线程数.
     * but:
     * 创建一个Thread约耗时2毫秒, 优化?
     */
    private final class RequestWorker extends Thread {
        /*private*/ Object result;
        /*private*/ Throwable ex;

        private RequestWorker() {
        }

        public void run() {
            try {
                if (File.class == loadType) {
                    while (sCurrFileLoadCount.get() >= MAX_FILE_LOAD_WORKER
                            && !HttpTask.this.isCancelled()) {
                        synchronized (sCurrFileLoadCount) {
                            try {
                                sCurrFileLoadCount.wait(100);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    sCurrFileLoadCount.incrementAndGet();
                }

                if (HttpTask.this.isCancelled()) {
                    throw new Callback.CancelledException("cancelled before request");
                }

                // intercept response
                if (interceptRequestListener != null) {
                    interceptRequestListener.beforeRequest(request);
                }

                try {
                    this.result = request.loadResult();
                } catch (Throwable ex) {
                    this.ex = ex;
                }

                // intercept response
                if (interceptRequestListener != null) {
                    interceptRequestListener.afterRequest(request);
                }

                if (this.ex != null) {
                    throw this.ex;
                }
            } catch (Throwable ex) {
                this.ex = ex;
                if (ex instanceof HttpException) {
                    HttpException httpEx = (HttpException) ex;
                    int errorCode = httpEx.getCode();
                    if (errorCode == 301 || errorCode == 302) {
                        RedirectHandler redirectHandler = params.getRedirectHandler();
                        if (redirectHandler != null) {
                            try {
                                RequestParams redirectParams = redirectHandler.getRedirectParams(request);
                                if (redirectParams != null) {
                                    if (redirectParams.getMethod() == null) {
                                        redirectParams.setMethod(params.getMethod());
                                    }
                                    // 开始重定向请求
                                    HttpTask.this.params = redirectParams;
                                    HttpTask.this.request = createNewRequest();
                                    this.ex = new HttpRedirectException(errorCode, httpEx.getMessage(), httpEx.getResult());
                                }
                            } catch (Throwable throwable) {
                                this.ex = ex;
                            }
                        }
                    }
                }
            } finally {
                if (File.class == loadType) {
                    synchronized (sCurrFileLoadCount) {
                        sCurrFileLoadCount.decrementAndGet();
                        sCurrFileLoadCount.notifyAll();
                    }
                }
            }
        }
    }

}
