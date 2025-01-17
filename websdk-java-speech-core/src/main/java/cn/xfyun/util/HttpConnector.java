package cn.xfyun.util;

import cn.xfyun.exception.HttpException;
import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.ByteArrayBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TODO
 *
 * @author : iflytek
 * @date : 2021年03月15日
 */
public class HttpConnector {
    private static final Logger log = LoggerFactory.getLogger(HttpConnector.class);
    private final PoolingHttpClientConnectionManager pool = new PoolingHttpClientConnectionManager();
    private CloseableHttpClient httpClient;

    private HttpConnector() {
    }

    public static HttpConnector build(int maxConnections, int connTimeout, int soTimeout, int retryCount) {
        HttpConnector connector = ConnectorBuilder.CONNECTOR;
        connector.pool.setMaxTotal(maxConnections);
        connector.pool.setDefaultMaxPerRoute(5);

        RequestConfig.Builder builder = RequestConfig.custom().setConnectionRequestTimeout(5000)
                .setConnectTimeout(connTimeout).setSocketTimeout(soTimeout);

        HttpClientBuilder httpClientBuilder = HttpClients.custom().setDefaultRequestConfig(builder.build())
                .setConnectionManager(connector.pool);
        if (retryCount > 0) {
            HttpRequestRetryHandler retryHandler = (exception, executionCount, context) -> {
                if (executionCount > retryCount) {
                    return false;
                }
                if (exception instanceof InterruptedIOException) {
                    return false;
                }
                if (exception instanceof UnknownHostException) {
                    return false;
                }
                if (exception instanceof SSLException) {
                    return false;
                }
                log.info("HttpConnector 第" + executionCount + "次重试");
                return true;
            };
            httpClientBuilder.setRetryHandler(retryHandler);
        }
        connector.httpClient = httpClientBuilder.build();
        return connector;
    }

    private static List<NameValuePair> convertMapToPair(Map<String, String> params) {
        List<NameValuePair> pairs = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            pairs.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }
        return pairs;
    }

    public String post(String url, Map<String, String> param) throws HttpException, IOException {
        HttpPost httpPost = new HttpPost(url);
        httpPost.setEntity(new UrlEncodedFormEntity(convertMapToPair(param), Consts.UTF_8));
        return doExecute(httpPost, Consts.UTF_8.toString());
    }

    public String post(String url, Map<String, String> param, byte[] body) throws HttpException, IOException {
        HttpPost httpPost = new HttpPost(url);
        MultipartEntityBuilder reqEntity = MultipartEntityBuilder.create();
        reqEntity.addPart("content", new ByteArrayBody(body, ContentType.DEFAULT_BINARY, param.get("slice_id")));

        for (Map.Entry<String, String> entry : param.entrySet()) {
            StringBody value = new StringBody(entry.getValue(), ContentType.create("text/plain", Consts.UTF_8));
            reqEntity.addPart(entry.getKey(), value);
        }

        HttpEntity httpEntry = reqEntity.build();
        httpPost.setEntity(httpEntry);
        return doExecute(httpPost, Consts.UTF_8.toString());
    }

    private String doExecute(HttpRequestBase requestBase, String charset) throws HttpException, IOException {
        String result;
        CloseableHttpResponse response = null;
        try {
            response = this.httpClient.execute(requestBase);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != HttpStatus.SC_OK) {
                log.warn("request: " + requestBase.getURI() + ", status: " + statusCode);
                throw new HttpException(requestBase.getURI() + "请求异常");
            }
            result = (charset == null) ? EntityUtils.toString(response.getEntity()) : EntityUtils.toString(response.getEntity(), charset);
        } finally {
            if (null != response) {
                EntityUtils.consumeQuietly(response.getEntity());
            }
            if (null != requestBase) {
                requestBase.releaseConnection();
            }
        }
        return result;
    }

    public void release() {
        try {
            this.httpClient.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ConnectorBuilder {
        private static final HttpConnector CONNECTOR = new HttpConnector();
    }
}
