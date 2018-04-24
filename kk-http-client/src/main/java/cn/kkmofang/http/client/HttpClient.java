package cn.kkmofang.http.client;


import android.content.Context;
import android.os.Handler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import cn.kkmofang.http.HttpOptions;
import cn.kkmofang.http.IHttp;
import cn.kkmofang.http.IHttpTask;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Created by zhanghailong on 2018/4/8.
 */

public class HttpClient implements IHttp {

    private final int MAX_SIZE = 409600;

    private final Map<Long,HttpTask> _httpTasks;
    private final Map<String,List<HttpTask>> _httpTasksWithKey;
    private final Map<String,Call> _calls;
    private final Handler _handler;
    private final OkHttpClient _client;
    private final Context _context;
    private long _id;
    private final Gson _gson;

    public HttpClient(Context context,long connectTimeout,long readTimeout,long writeTimeout) {
        _id = 0;
        _handler = new Handler();
        _client = (new OkHttpClient.Builder())
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .writeTimeout(writeTimeout, TimeUnit.SECONDS)
                .build();
        _httpTasks = new TreeMap<>();
        _httpTasksWithKey = new TreeMap<>();
        _calls = new TreeMap<>();
        _context = context;
        _gson = (new GsonBuilder()).create();
    }


    @Override
    public IHttpTask send(HttpOptions options, Object weakObject) {

        HttpTask httpTask = new HttpTask(this,options, ++ _id,weakObject);

        final long id = httpTask.getId();
        final String key = httpTask.key();
        final String type = options.type;

        if(key != null && _httpTasksWithKey.containsKey(key)) {

            _httpTasksWithKey.get(key).add(httpTask);

            _httpTasks.put(httpTask.getId(),httpTask);

            return httpTask;
        }

        Call call = _client.newCall(httpTask.newRequest(_context));

        if(key != null) {
            _calls.put(key, call);
            List<HttpTask> httpTasks = new LinkedList<>();
            httpTasks.add(httpTask);
            _httpTasksWithKey.put(key, httpTasks);
            _httpTasks.put(httpTask.getId(), httpTask);
        } else {
            httpTask.setCall(call);
            _httpTasks.put(httpTask.getId(), httpTask);
        }

        final WeakReference<HttpClient> httpClient = new WeakReference<HttpClient>(this);

        call.enqueue(new Callback() {

            @Override
            public void onFailure(Call call, IOException e) {
                HttpClient v = httpClient.get();
                if(v != null) {
                    v.onFailure(id,key,call,e);
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                HttpClient v = httpClient.get();
                if(v != null) {
                    v.onResponse(id,key,type,call,response);
                }
            }
        });

        if(options.timeout > 0) {

            final WeakReference<Call> weakCall = new WeakReference<Call>(call);

            _handler.postDelayed(new Runnable() {

                @Override
                public void run() {
                    Call v = weakCall.get();
                    if(v != null) {
                        v.cancel();
                    }
                }

            }, options.timeout * 1000);
        }

        return httpTask;
    }

    protected void onFailure(final long id, final String key , Call call, final IOException e) {


        _handler.post(new Runnable() {
            @Override
            public void run() {

                if(key != null) {

                    if(_httpTasksWithKey.containsKey(key)) {

                        for(HttpTask httpTask : _httpTasksWithKey.get(key)) {

                            httpTask.onFail(e);

                            long id = httpTask.getId();

                            if(_httpTasks.containsKey(id)) {
                                _httpTasks.remove(id);
                            }
                        }

                        _httpTasksWithKey.remove(key);
                        _calls.remove(key);
                    }

                } else if(_httpTasks.containsKey(id)){

                    HttpTask httpTask = _httpTasks.get(id);

                    httpTask.onFail(e);

                    _httpTasks.remove(id);
                }
            }
        });


    }

    protected void onResponse(final long id, final String key , String type, Call call, Response response) throws IOException {

        if(call.isCanceled()) {
            return;
        }

        final Map<String,Object> headers = new TreeMap<>();

        {
            Headers vs = response.headers();
            for(int i=0;i<vs.size();i++) {
                String name = vs.name(i);
                String value = vs.value(i);
                headers.put(name,value);
            }
        }

        Object data = null;
        Exception exception = null;

        final int code = response.code();

        if(code == 200 || code == 206) {

            try {

                if (key != null) {

                    String tmppath = HttpOptions.cacheTmpPathWithKey(_context, key);
                    File dir = (new File(tmppath)).getParentFile();
                    if(!dir.exists()) {
                        dir.mkdirs();
                    }
                    ResponseBody body = response.body();
                    InputStream in = body.byteStream();

                    try {

                        FileOutputStream out = new FileOutputStream(tmppath, response.code() == 206);

                        try {

                            byte[] bytes = new byte[MAX_SIZE];
                            int n;

                            while ((n = in.read(bytes)) > 0) {
                                out.write(bytes, 0, n);
                            }

                        } finally {
                            out.close();
                        }

                    } finally {
                        in.close();
                    }

                    data = HttpOptions.cachePathWithKey(_context, key);

                    File fd = new File(tmppath);

                    fd.renameTo(new File((String) data));

                } else if (HttpOptions.TYPE_DATA.equals(type)) {
                    data = response.body().bytes();
                } else if(HttpOptions.TYPE_JSON.equals(type)) {
                    String text = response.body().string();
                    data = decodeJSON(text);
                } else {
                    data = response.body().string();
                }

            } catch (Exception ex) {
                exception = ex;
            }

        }

        final Object fdata = data;
        final Exception fexception = exception;

        _handler.post(new Runnable() {
            @Override
            public void run() {

                if(key != null) {

                    if(_httpTasksWithKey.containsKey(key)) {

                        for(HttpTask httpTask : _httpTasksWithKey.get(key)) {

                            httpTask.onLoad(fdata,fexception);

                            long id = httpTask.getId();

                            if(_httpTasks.containsKey(id)) {
                                _httpTasks.remove(id);
                            }
                        }

                        _httpTasksWithKey.remove(key);
                        _calls.remove(key);
                    }

                } else if(_httpTasks.containsKey(id)){

                    HttpTask httpTask = _httpTasks.get(id);

                    httpTask.onLoad(fdata,fexception);

                    _httpTasks.remove(id);
                }
            }
        });
    }

    @Override
    public void cancel(Object weakObject) {

        Set<Map.Entry<Long,HttpTask>> entrys = _httpTasks.entrySet();

        Iterator<Map.Entry<Long,HttpTask>> i = entrys.iterator();

        while(i.hasNext()) {
            Map.Entry<Long,HttpTask> entry = i.next();
            HttpTask httpTask = entry.getValue();
            if(weakObject == null || httpTask.weakObject() == weakObject) {
                remove(httpTask,httpTask.key());
                httpTask.release();
                i.remove();
            }
        }

    }

    @Override
    public String encodeJSON(Object object) {
        return _gson.toJson(object);
    }

    @Override
    public Object decodeJSON(String text) {
        return _gson.fromJson(text,Object.class);
    }

    void remove(HttpTask httpTask,String key) {
        if(key != null && _httpTasksWithKey.containsKey(key)) {
            List<HttpTask> httpTasks = _httpTasksWithKey.get(key);
            httpTasks.remove(httpTask);
            if(httpTasks.isEmpty()) {
                _httpTasksWithKey.remove(key);
                if(_calls.containsKey(key)) {
                    Call call = _calls.get(key);
                    call.cancel();
                    _calls.remove(key);
                }
            }
        }
    }

    void remove(HttpTask httpTask) {

        long id = httpTask.getId();

        if(_httpTasks.containsKey(id)) {

            _httpTasks.remove(id);

            remove(httpTask,httpTask.key());

        }

    }



}
