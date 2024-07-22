package burp.backend.platform;

import burp.backend.IBackend;
import burp.poc.IPOC;
import burp.utils.HttpUtils;
import burp.utils.Utils;
import okhttp3.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static burp.utils.HttpUtils.GetDefaultRequest;

public class DnslogCN implements IBackend {
    OkHttpClient client = new OkHttpClient().newBuilder().cookieJar(new CookieJar() {
                private final HashMap<String, List<Cookie>> cookieStore = new HashMap<>();

                @Override
                public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                    cookieStore.put(url.host(), cookies);
                }

                @Override
                public List<Cookie> loadForRequest(HttpUrl url) {
                    List<Cookie> cookies = cookieStore.get(url.host());
                    return cookies != null ? cookies : new ArrayList<Cookie>();
                }
            }).connectTimeout(50, TimeUnit.SECONDS).
            callTimeout(50, TimeUnit.SECONDS).
            readTimeout(3, TimeUnit.MINUTES).build();
    String platformUrl = "http://www.dnslog.cn/";
    String rootDomain = "";
    String dnsLogResultCache = "";
    Timer timer = new Timer();

    public DnslogCN() {
        this.initDomain();
    }

    private void initDomain() {
        try {
            Utils.Callback.printOutput("get domain...");
            Response resp = client.newCall(GetDefaultRequest(platformUrl + "/getdomain.php?t=0." + Math.abs(Utils.getRandomLong())).build()).execute();
            rootDomain = resp.body().string();
            Utils.Callback.printOutput(String.format("Domain: %s", rootDomain));
            startSessionHeartbeat();
        } catch (Exception ex) {
            Utils.Callback.printError("initDomain failed: " + ex.getMessage());
        }
    }

    @Override
    public boolean supportBatchCheck() {
        return false;
    }

    private void startSessionHeartbeat() {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                flushCache();
            }
        }, 0, 2 * 60 * 1000); //2min
    }

    @Override
    public void close() {
        timer.cancel();
    }

    @Override
    public String getName() {
        return "Dnslog.cn";
    }

    @Override
    public String getNewPayload() {
        return Utils.getCurrentTimeMillis() + Utils.GetRandomString(5) + "." + rootDomain;
    }

    @Override
    public String getNewPayload(String reqDomain) {
        return reqDomain + Utils.GetRandomString(5) + "." + rootDomain;
    }

    public boolean flushCache() {
        try {
            Response resp = client.newCall(HttpUtils.GetDefaultRequest(platformUrl + "getrecords.php?t=0." + Math.abs(Utils.getRandomLong())).build()).execute();
            dnsLogResultCache = resp.body().string().toLowerCase();
            return true;
        } catch (Exception ex) {
            Utils.StdErrPrintln(String.format("Get Dnslog Result Failed!: %s", ex.getMessage()));
            return false;
        }
    }

    @Override
    public boolean flushCache(int count) {
        return flushCache();
    }

    @Override
    public boolean CheckResult(String domain) {
        return dnsLogResultCache.contains(domain.toLowerCase());
    }
    @Override
    public String[] batchCheck(String[] payloads) {
        return new String[0];
    }
    @Override
    public boolean getState() {
        return rootDomain != "";
    }

    @Override
    public int[] getSupportedPOCTypes() {
        return new int[]{IPOC.POC_TYPE_LDAP, IPOC.POC_TYPE_RMI, IPOC.POC_TYPE_DNS};
    }
}
