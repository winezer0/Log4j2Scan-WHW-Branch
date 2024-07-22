package burp.backend.platform;

import burp.backend.IBackend;
import burp.poc.IPOC;
import burp.utils.Config;
import burp.utils.HttpUtils;
import burp.utils.Utils;
import okhttp3.OkHttpClient;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;


public class Ceye implements IBackend {
    OkHttpClient client = new OkHttpClient().newBuilder().
            connectTimeout(3000, TimeUnit.SECONDS).
            callTimeout(3000, TimeUnit.SECONDS).build();
    String platformUrl = "http://api.ceye.io/";
    String rootDomain;
    String token;
    String cache = "";

    public Ceye() {
        this.rootDomain = Config.get(Config.CEYE_IDENTIFIER);
        this.token = Config.get(Config.CEYE_TOKEN);
    }

    @Override
    public boolean supportBatchCheck() {
        return false;
    }

    @Override
    public String[] batchCheck(String[] payloads) {
        return new String[0];
    }

    @Override
    public String getName() {
        return "Ceye.io";
    }

    @Override
    public String getNewPayload() {
        return Utils.getCurrentTimeMillis() + Utils.GetRandomString(5).toLowerCase() + "." + rootDomain;
    }

    @Override
    public String getNewPayload(String reqDomain) {
        return reqDomain + Utils.GetRandomString(5).toLowerCase() + "." + rootDomain;
    }

    @Override
    public boolean CheckResult(String domain) {
        return cache.contains(domain);
    }

    @Override
    public boolean flushCache(int count) {
        return flushCache();
    }

    @Override
    public boolean flushCache() {
        try {
            Response resp = client.newCall(HttpUtils.GetDefaultRequest(platformUrl + "v1/records?token=" + token + "&type=dns").build()).execute();
            cache = resp.body().string().toLowerCase();
            return true;
        } catch (Exception ex) {
            System.out.println(ex);
            return false;
        }
    }

    @Override
    public boolean getState() {
        return true;
    }

    @Override
    public void close() {
    }

    @Override
    public int[] getSupportedPOCTypes() {
        return new int[]{IPOC.POC_TYPE_LDAP, IPOC.POC_TYPE_RMI};
    }
}
