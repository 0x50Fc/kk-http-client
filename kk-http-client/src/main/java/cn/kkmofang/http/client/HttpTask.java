package cn.kkmofang.http.client;

import android.content.Context;
import android.os.Build;
import android.webkit.WebSettings;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Map;

import cn.kkmofang.http.HttpOptions;
import cn.kkmofang.http.IHttpTask;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * Created by hailong11 on 2018/4/8.
 */

class HttpTask implements IHttpTask{

    private final String _key;
    private final long _id;
    private final HttpOptions _options;
    private final WeakReference<HttpClient> _client;
    private final WeakReference<Object> _weakObject;
    private WeakReference<Call> _call;

    HttpTask(HttpClient client,HttpOptions options,long id,Object weakObject) {
        _options = options;
        _client = new WeakReference<HttpClient>(client);
        _weakObject = new WeakReference<Object>(weakObject);
        _id = id;
        _key = options.key();
        _call = null;
    }

    public long getId() {
        return _id;
    }

    public Object weakObject() {
        return _weakObject.get();
    }

    public String key() {
        return _key;
    }

    public void setCall(Call call) {
        _call = new WeakReference<Call>(call);
    }

    public Call call() {
        return _call.get();
    }

    public Request newRequest(Context context) {

        Request.Builder b = (new Request.Builder());

        b.url(_options.absoluteUrl());

        MediaType mediaType = MediaType.parse("text/plain");

        if(_options.headers != null) {
            for(String key : _options.headers.keySet()) {
                String v = HttpOptions.stringValue(_options.headers.get(key),"");
                b.header(key,v);
                if("Content-Type".equals(key)) {
                    mediaType = MediaType.parse(v);
                }
            }
        }

        b.header("User-Agent",getUserAgent(context));

        if(HttpOptions.METHOD_POST.equals(_options.method)) {

            if(_options.data instanceof String) {
                b.method(_options.method, RequestBody.create(mediaType,(String) _options.data));
            } else if(_options.data instanceof Map) {
                boolean multipart = false;
                Map<String,Object> m = (Map<String,Object>) _options.data;

                for(String key : m.keySet()) {
                    Object v = m.get(key);
                    if(v instanceof Map) {
                        multipart = true;
                        break;
                    }
                }

                if(multipart) {

                    MultipartBody.Builder multipartBody = new MultipartBody.Builder();

                    multipartBody.setType(MultipartBody.FORM);

                    for(String key : m.keySet()) {
                        Object v = m.get(key);
                        if(v instanceof Map) {
                            Map<String,Object> fd = (Map<String,Object>) v;
                            if(fd.containsKey("uri") && fd.containsKey("name") && fd.containsKey("type")) {
                                String uri = HttpOptions.stringValue(fd.get("uri"),"");
                                String name = HttpOptions.stringValue(fd.get("name"),"");
                                String type = HttpOptions.stringValue(fd.get("type"),"");
                                String path = HttpOptions.path(context,uri);
                                multipartBody.addFormDataPart(key,name,RequestBody.create(MediaType.parse(type),new File(path)));
                            }

                        } else {
                            multipartBody.addFormDataPart(key,HttpOptions.stringValue(v,""));
                        }
                    }

                    b.method(_options.method,multipartBody.build());

                } else {

                    FormBody.Builder formBody = new FormBody.Builder();

                    for(String key : m.keySet()) {
                        String v = HttpOptions.stringValue( m.get(key),"");
                        formBody.add(key, v);
                    }

                    b.method(_options.method,formBody.build());
                }


            }



        }

        return b.build();
    }

    void release() {
        if(_call != null) {
            Call v = _call.get();
            if(v != null) {
                v.cancel();
            }
        }
    }

    void onLoad(Object data,Exception error) {
        HttpOptions.OnLoad v = _options.onload;
        if(v != null) {
            v.on(data,error,_weakObject.get());
        }
    }

    void onFail(Exception error) {
        HttpOptions.OnFail v = _options.onfail;
        if(v != null) {
            v.on(error,_weakObject.get());
        }
    }

    void onResponse(int code, String status, Map<String,Object> headers) {
        HttpOptions.OnResponse v = _options.onresponse;
        if(v != null) {
            v.on(code,status,headers,_weakObject.get());
        }
    }

    void onProcess(long value,long maxValue,Object weakObject) {
        HttpOptions.OnProcess v = _options.onprocess;
        if(v != null) {
            v.on(value,maxValue,_weakObject.get());
        }
    }

    @Override
    public void cancel() {
        release();
        HttpClient v = _client.get();
        if(v != null) {
            v.remove(this);
        }
    }

    public static String getUserAgent(Context context) {
        String userAgent = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            try {
                userAgent = WebSettings.getDefaultUserAgent(context);
            } catch (Exception e) {
                userAgent = System.getProperty("http.agent");
            }
        } else {
            userAgent = System.getProperty("http.agent");
        }
        StringBuffer sb = new StringBuffer();
        for (int i = 0, length = userAgent.length(); i < length; i++) {
            char c = userAgent.charAt(i);
            if (c <= '\u001f' || c >= '\u007f') {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

}
