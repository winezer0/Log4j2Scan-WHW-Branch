package burp.scanner;

import burp.*;
import burp.backend.IBackend;
import burp.backend.platform.*;
import burp.poc.IPOC;
import burp.ui.tabs.BackendUIHandler;
import burp.utils.*;
import net.jodah.expiringmap.ExpiringMap;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static burp.ui.tabs.POCUIHandler.defaultEnabledPocIds;

public class Log4j2Scanner implements IScannerCheck {
    private BurpExtender parent;
    private IExtensionHelpers helper;
    public IBackend backend;

    private final String[] HEADER_BLACKLIST = new String[]{
            "content-length",
            "cookie",
            "host",
            "content-type"
    };
    private final String[] HEADER_GUESS = new String[]{
            "User-Agent",
            "X-Client-IP",
            "X-Remote-IP",
            "X-Remote-Addr",
            "X-Forwarded-For",
            "X-Originating-IP",
            "CF-Connecting-IP",
            "True-Client-IP",
            "Originating-IP",
            "X-Real-IP",
            "Client-IP",
            "X-Wap-Profile",
            "X-Api-Version",
            "Sec-Ch-Ua",
            "Sec-Ch-Ua-Platform",
            "Upgrade-Insecure-Requests",
            "Accept",
            "Sec-Fetch-Site",
            "Sec-Fetch-Mode",
            "Sec-Fetch-User",
            "Sec-Fetch-Dest",
            "Accept-Encoding",
            "Accept-Language",
            "Referer",
            "Forwarded",
            "Contact",
            "If-Mondified-Since",
            "X-Custom-IP-Authorization",
            "X-Forwarded-Host",
            "X-Forwarded-Server",
            "X-Host",
            "X-Original-URL",
            "X-Rewrite-URL",
            "Connection"
    };

    private final String[] STATIC_FILE_EXT = new String[]{
            "png",
            "jpg",
            "jpeg",
            "gif",
            "pdf",
            "bmp",
            "js",
            "css",
            "ico",
            "woff",
            "woff2",
            "ttf",
            "otf",
            "ttc",
            "svg",
            "psd",
            "exe",
            "zip",
            "rar",
            "7z",
            "msi",
            "tar",
            "gz",
            "mp3",
            "mp4",
            "mkv",
            "swf",
            "xls",
            "xlsx",
            "doc",
            "docx",
            "ppt",
            "pptx",
            "iso",
            "map"
    };

    private IPOC[] pocs;

    private Config.FuzzMode fuzzMode;

    Map<String, ScanItem> cacheScanMap = ExpiringMap.builder()
            .expiration(5, TimeUnit.MINUTES)
            .build();


    Timer cacheCheckTimer = new Timer();

    public Log4j2Scanner(final BurpExtender newParent) {
        this.parent = newParent;
        this.helper = newParent.helpers;
        this.loadConfig();
        if (this.backend.getState()) {
            parent.stdout.println("Log4j2Scan loaded successfully!\r\n");
            startCacheChecker();
        } else {
            parent.stdout.println("Backend init failed!\r\n");
            cacheCheckTimer.cancel();
        }
    }

    private void startCacheChecker() {
        cacheCheckTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                cacheChecker();
            }
        }, 0, 30 * 1000); //30s
    }

    public void cacheChecker() {
        if (cacheScanMap.size() == 0) return;
        Utils.StdoutPrintln("Has missing match scan point: " + cacheScanMap.size() + ",recheck DNSLog result..");
        int found = 0;
        if (backend.flushCache(cacheScanMap.size())) {
            Map.Entry[] tmpMap = cacheScanMap.entrySet().toArray(new Map.Entry[0]);
            for (Map.Entry<String, ScanItem> domainItem :
                    tmpMap) {
                ScanItem item = domainItem.getValue();
                boolean hasIssue = backend.CheckResult(domainItem.getKey());
                if (hasIssue) {
                    parent.callbacks.addScanIssue(getIssue(item.BaseRequest, item.RequestInfo, item));
                    cacheScanMap.remove(domainItem.getKey());
                    found++;
                }
            }
            if (found > 0) {
                Utils.StdoutPrintln("found issue: " + found);
            }
        } else {
            parent.stdout.println("get backend result failed!\r\n");
        }
    }

    public void close() {
        if (this.backend != null) {
            this.backend.close();
        }
        cacheCheckTimer.cancel();
    }

    public boolean getState() {
        try {
            return this.backend.getState() && getSupportedPOCs().size() > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private void loadConfig() {
        BackendUIHandler.Backends currentBackend = BackendUIHandler.Backends.valueOf(Config.get(Config.CURRENT_BACKEND, BackendUIHandler.Backends.BurpCollaborator.name()));
        JSONArray enabled_poc_ids = new JSONArray(Config.get(Config.ENABLED_POC_IDS, new JSONArray(defaultEnabledPocIds).toString()));
        try {
            switch (currentBackend) {
                case Ceye:
                    this.backend = new Ceye();
                    break;
                case DnslogCN:
                    this.backend = new DnslogCN();
                    break;
                case RevSuitDNS:
                    this.backend = new RevSuitDNS();
                    break;
                case RevSuitRMI:
                    this.backend = new RevSuitRMI();
                    break;
                case GoDnslog:
                    this.backend = new GoDnslog();
                    break;
                case SelfDigPm:
                    this.backend = new SelfDigPm();
                    break;
                case BurpCollaborator:
                    this.backend = new BurpCollaborator();
                    break;
                case DigPm:
                    this.backend = new DigPm();
                    break;
                case Eyes:
                    this.backend = new Eyes();
            }
            List<Integer> enabled_poc_ids_list = new ArrayList<>();
            enabled_poc_ids.forEach(e -> enabled_poc_ids_list.add((int) e));
            this.pocs = Utils.getPOCs(enabled_poc_ids.toList().toArray(new Integer[0])).values().toArray(new IPOC[0]);
        } catch (Exception ex) {
            parent.stdout.println(ex);
        } finally {
            if (this.backend == null || !this.backend.getState()) {
                parent.stdout.println("Load backend from config failed! fallback to dnslog.cn....");
                this.backend = new DnslogCN();
                this.pocs = Utils.getPOCs(new Integer[]{1, 2, 3, 4, 11}).values().toArray(new IPOC[0]);
            }
        }
    }

    public String urlencodeForTomcat(String exp) {
        exp = exp.replace("{", "%7b");
        exp = exp.replace("}", "%7d");
        return exp;
    }

    @Override
    public List<IScanIssue> doPassiveScan(IHttpRequestResponse baseRequestResponse) {
        Config.ScanMode scanMode = Config.ScanMode.valueOf(Config.get(Config.SCAN_MODE, Config.ScanMode.Passive.name()));
        if (scanMode == Config.ScanMode.Passive)
            return doScan(baseRequestResponse);
        else return null;
    }

    @Override
    public List<IScanIssue> doActiveScan(IHttpRequestResponse baseRequestResponse, IScannerInsertionPoint insertionPoint) {
        return doScan(baseRequestResponse);
    }

    private List<IScanIssue> doScan(IHttpRequestResponse baseRequestResponse) {
        this.fuzzMode = Config.FuzzMode.valueOf(Config.get(Config.FUZZ_MODE, Config.FuzzMode.EachFuzz.name()));
        IRequestInfo req = this.parent.helpers.analyzeRequest(baseRequestResponse);
        List<IScanIssue> issues = new ArrayList<>();
        if (!isStaticFile(req.getUrl().toString())) {
            Utils.StdoutPrintln(String.format("Scanning: %s", req.getUrl()));
            Map<String, ScanItem> domainMap = new HashMap<>();
            if (this.fuzzMode == Config.FuzzMode.EachFuzz) {
                domainMap.putAll(paramsFuzz(baseRequestResponse, req));
                if (Config.getBoolean(Config.ENABLED_FUZZ_HEADER, true)) {
                    domainMap.putAll(headerFuzz(baseRequestResponse, req));
                }
                if (Config.getBoolean(Config.ADD_FUZZ_HEADER, true)) {
                    domainMap.putAll(headerAdd(baseRequestResponse, req));
                }
            } else {
                domainMap.putAll(crazyFuzz(baseRequestResponse, req));
            }
            if (Config.getBoolean(Config.ENABLED_FUZZ_BAD_JSON, false)) {
                domainMap.putAll(badJsonFuzz(baseRequestResponse, req));
            }
            try {
                Thread.sleep(2000); //sleep 2s, wait for network delay.
            } catch (InterruptedException e) {
                parent.stdout.println(e);
            }
            try {
                issues.addAll(finalCheck(baseRequestResponse, req, domainMap));
            } catch (InterruptedException e) {
                parent.stdout.println(e);
            }
            Utils.StdoutPrintln("Scan complete: " + req.getUrl() + " - " + (issues.size() > 0 ? String.format("found %d issue.", issues.size()) : "No issue found."));
        }
        return issues;
    }

    private boolean isStaticFile(String url) {
        return Arrays.stream(STATIC_FILE_EXT).anyMatch(s -> s.equalsIgnoreCase(HttpUtils.getUrlFileExt(url)));
    }

    private Collection<IPOC> getSupportedPOCs() {
        return Arrays.stream(pocs).filter(p -> Arrays.stream(backend.getSupportedPOCTypes()).anyMatch(c -> c == p.getType())).collect(Collectors.toList());
    }

    private Map<String, ScanItem> crazyFuzz(IHttpRequestResponse baseRequestResponse, IRequestInfo req) {
        String reqHost = Utils.getHost(baseRequestResponse);
        List<String> headers = req.getHeaders();
        Map<String, ScanItem> domainMap = new HashMap<>();
        for (IPOC poc : getSupportedPOCs()) {
            try {
                byte[] rawRequest = baseRequestResponse.getRequest();
                byte[] tmpRawRequest = rawRequest;
                byte[] rawBody = Arrays.copyOfRange(rawRequest, req.getBodyOffset(), rawRequest.length);
                List<String> tmpHeaders = new ArrayList<>(headers);
                Map<String, String> domainHeaderMap = new HashMap<>();
                if (Config.getBoolean(Config.ENABLED_FUZZ_HEADER, true)) {
                    List<String> guessHeaders = new ArrayList(Arrays.asList(HEADER_GUESS));
                    for (int i = 1; i < headers.size(); i++) {
                        HttpHeader header = new HttpHeader(headers.get(i));
                        if (Arrays.stream(HEADER_BLACKLIST).noneMatch(h -> h.equalsIgnoreCase(header.Name))) {
                            List<String> needSkipheader = guessHeaders.stream().filter(h -> h.equalsIgnoreCase(header.Name)).collect(Collectors.toList());
                            needSkipheader.forEach(guessHeaders::remove);
                            String tmpDomain = backend.getNewPayload(reqHost);
                            header.Value = poc.generate(tmpDomain);
                            if (header.Name.equalsIgnoreCase("accept")) {
                                header.Value = "*/*;" + header.Value;
                            }
                            tmpHeaders.set(i, header.toString());
                            domainHeaderMap.put(header.Name, tmpDomain);
                        }
                    }
                    for (String headerName : guessHeaders) {
                        String tmpDomain = backend.getNewPayload(reqHost);
                        tmpHeaders.add(String.format("%s: %s", headerName, poc.generate(tmpDomain)));
                        domainHeaderMap.put(headerName, tmpDomain);
                    }
                }
                int skipLength = 0;
                int paramsIndex = 0;
                Map<Integer, ParamReplace> paramMap = new HashMap<>();
                Map<String, IParameter> domainParamMap = new HashMap<>();
                tmpRawRequest = parent.helpers.buildHttpMessage(tmpHeaders, rawBody);
                IRequestInfo tmpReqInfo = parent.helpers.analyzeRequest(tmpRawRequest);
                for (IParameter param : tmpReqInfo.getParameters()) {
                    String tmpDomain = backend.getNewPayload(reqHost);
                    String exp = poc.generate(tmpDomain);
                    boolean inHeader = false;
                    switch (param.getType()) {
                        case IParameter.PARAM_URL:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_URL, true))
                                continue;
                            exp = helper.urlEncode(exp);
                            exp = urlencodeForTomcat(exp);
                            inHeader = true;
                            break;
                        case IParameter.PARAM_COOKIE:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_COOKIE, true))
                                continue;
                            exp = helper.urlEncode(exp);
                            exp = urlencodeForTomcat(exp);
                            inHeader = true;
                            break;
                        case IParameter.PARAM_BODY:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_BODY_FORM, true))
                                continue;
                            exp = helper.urlEncode(exp);
                            exp = urlencodeForTomcat(exp);
                            break;
                        case IParameter.PARAM_JSON:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_BODY_JSON, true))
                                continue;
                            break;
                        case IParameter.PARAM_MULTIPART_ATTR:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_BODY_MULTIPART, true))
                                continue;
                            break;
                        case IParameter.PARAM_XML:
                        case IParameter.PARAM_XML_ATTR:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_BODY_XML, true))
                                continue;
                            break;
                    }
                    if (inHeader) {
                        IParameter newParam = helper.buildParameter(param.getName(), exp, param.getType());
                        tmpRawRequest = helper.updateParameter(tmpRawRequest, newParam);
                    } else {
                        paramMap.put(paramsIndex++, new ParamReplace(
                                param.getValueStart() - tmpReqInfo.getBodyOffset() + skipLength,
                                param.getValueEnd() - tmpReqInfo.getBodyOffset() + skipLength,
                                exp));
                        skipLength += exp.length() - (param.getValueEnd() - param.getValueStart());
                    }
                    domainParamMap.put(tmpDomain, param);
                }
                tmpRawRequest = helper.buildHttpMessage(helper.analyzeRequest(tmpRawRequest).getHeaders(), updateParams(rawBody, paramMap));
                IHttpRequestResponse tmpReq = sendRequest(baseRequestResponse.getHttpService(), tmpRawRequest);
                for (Map.Entry<String, String> domainHeader : domainHeaderMap.entrySet()) {
                    domainMap.put(domainHeader.getValue(), new ScanItem(domainHeader.getKey(), tmpReq, baseRequestResponse, tmpRawRequest));
                }
                for (Map.Entry<String, IParameter> domainParam : domainParamMap.entrySet()) {
                    domainMap.put(domainParam.getKey(), new ScanItem(domainParam.getValue(), tmpReq, baseRequestResponse, tmpRawRequest));
                }
            } catch (Exception ex) {
                ex.printStackTrace(parent.stderr);
            }
        }


        return domainMap;
    }

    private byte[] updateParams(byte[] rawBody, Map<Integer, ParamReplace> paramMap) {
        byte[] body = rawBody;
        for (int i = 0; i < paramMap.size(); i++) {
            ParamReplace paramReplace = paramMap.get(i);
            body = Utils.Replace(body, new int[]{paramReplace.Start, paramReplace.End}, paramReplace.Payload.getBytes(StandardCharsets.UTF_8));
        }
        return body;
    }

    private Map<String, ScanItem> headerFuzz(IHttpRequestResponse baseRequestResponse, IRequestInfo req) {
        String reqHost = Utils.getHost(baseRequestResponse);
        List<String> headers = req.getHeaders();
        Map<String, ScanItem> domainMap = new HashMap<>();
        try {
            byte[] rawRequest = baseRequestResponse.getRequest();
            List<String> guessHeaders = new ArrayList(Arrays.asList(HEADER_GUESS));
            for (int i = 1; i < headers.size(); i++) {
                HttpHeader header = new HttpHeader(headers.get(i));
                if (Arrays.stream(HEADER_BLACKLIST).noneMatch(h -> h.equalsIgnoreCase(header.Name))) {
                    List<String> needSkipheader = guessHeaders.stream().filter(h -> h.equalsIgnoreCase(header.Name)).collect(Collectors.toList());
                    needSkipheader.forEach(guessHeaders::remove);
                    for (IPOC poc : getSupportedPOCs()) {
                        List<String> tmpHeaders = new ArrayList<>(headers);
                        String tmpDomain = backend.getNewPayload(reqHost);
                        header.Value = poc.generate(tmpDomain);
                        tmpHeaders.set(i, header.toString());
                        byte[] tmpRawRequest = helper.buildHttpMessage(tmpHeaders, Arrays.copyOfRange(rawRequest, req.getBodyOffset(), rawRequest.length));
                        IHttpRequestResponse tmpReq = sendRequest(baseRequestResponse.getHttpService(), tmpRawRequest);
                        domainMap.put(tmpDomain, new ScanItem(header.Name, tmpReq, baseRequestResponse, tmpRawRequest));
                    }
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace(parent.stderr);
        }
        return domainMap;
    }

    private Map<String, ScanItem> headerAdd(IHttpRequestResponse baseRequestResponse, IRequestInfo req) {
        String reqHost = Utils.getHost(baseRequestResponse);
        List<String> headers = req.getHeaders();
        Map<String, ScanItem> domainMap = new HashMap<>();
        try {
            byte[] rawRequest = baseRequestResponse.getRequest();
            List<String> guessHeaders = new ArrayList(Arrays.asList(HEADER_GUESS));
            for (IPOC poc : getSupportedPOCs()) {
                List<String> tmpHeaders = new ArrayList<>(headers);
                Map<String, String> domainHeaderMap = new HashMap<>();
                for (String headerName : guessHeaders) {
                    String tmpDomain = backend.getNewPayload(reqHost);
                    tmpHeaders.add(String.format("%s: %s", headerName, poc.generate(tmpDomain)));
                    domainHeaderMap.put(headerName, tmpDomain);
                }
                byte[] tmpRawRequest = helper.buildHttpMessage(tmpHeaders, Arrays.copyOfRange(rawRequest, req.getBodyOffset(), rawRequest.length));
                IHttpRequestResponse tmpReq = sendRequest(baseRequestResponse.getHttpService(), tmpRawRequest);
                for (Map.Entry<String, String> domainHeader : domainHeaderMap.entrySet()) {
                    domainMap.put(domainHeader.getValue(), new ScanItem(domainHeader.getKey(), tmpReq, baseRequestResponse, tmpRawRequest));
                }
            }

        } catch (Exception ex) {
            ex.printStackTrace(parent.stderr);
        }
        return domainMap;
    }

    private Map<String, ScanItem> badJsonFuzz(IHttpRequestResponse baseRequestResponse, IRequestInfo req) {
        String reqHost = Utils.getHost(baseRequestResponse);
        Map<String, ScanItem> domainMap = new HashMap<>();
        boolean canFuzz = false;
        List<String> rawHeaders = req.getHeaders();
        List<String> tmpHeaders = new ArrayList<>(rawHeaders);
        for (int i = 1; i < rawHeaders.size(); i++) {
            HttpHeader header = new HttpHeader(rawHeaders.get(i));
            if (header.Name.equalsIgnoreCase("content-type")) {  //has content-type header, maybe accept application/json?
                header.Value = "application/json;charset=UTF-8";
                tmpHeaders.set(i, header.toString());
                canFuzz = true;
            }
        }
        if (canFuzz) {
            for (IPOC poc : getSupportedPOCs()) {
                String tmpDomain = backend.getNewPayload(reqHost);
                String exp = poc.generate(tmpDomain);
                String finalPaylad = String.format("{\"%s\":%d%s%d}",   //try to create a bad-json.
                        Utils.GetRandomString(Utils.GetRandomNumber(3, 10)),
                        Utils.GetRandomNumber(100, Integer.MAX_VALUE),
                        exp,
                        Utils.GetRandomNumber(100, Integer.MAX_VALUE));
                IParameter fakeParam = helper.buildParameter("Bad-json Fuzz", exp, IParameter.PARAM_JSON);
                byte[] newRequest = helper.buildHttpMessage(tmpHeaders, finalPaylad.getBytes(StandardCharsets.UTF_8));
                IHttpRequestResponse tmpReq = sendRequest(baseRequestResponse.getHttpService(), newRequest);
                domainMap.put(tmpDomain, new ScanItem(fakeParam, tmpReq, baseRequestResponse, newRequest));
            }
        }
        return domainMap;
    }

    private Map<String, ScanItem> paramsFuzz(IHttpRequestResponse baseRequestResponse, IRequestInfo req) {
        String reqHost = Utils.getHost(baseRequestResponse);
        Map<String, ScanItem> domainMap = new HashMap<>();
        byte[] rawRequest = baseRequestResponse.getRequest();
        for (IParameter param : req.getParameters()) {
            for (IPOC poc : getSupportedPOCs()) {
                try {
                    String tmpDomain = backend.getNewPayload(reqHost);
                    byte[] tmpRawRequest;
                    String exp = poc.generate(tmpDomain);
                    boolean inHeader = false;
                    switch (param.getType()) {
                        case IParameter.PARAM_URL:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_URL, true))
                                continue;
                            exp = helper.urlEncode(exp);
                            exp = urlencodeForTomcat(exp);
                            inHeader = true;
                            break;
                        case IParameter.PARAM_COOKIE:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_COOKIE, true))
                                continue;
                            exp = helper.urlEncode(exp);
                            exp = urlencodeForTomcat(exp);
                            inHeader = true;
                            break;
                        case IParameter.PARAM_BODY:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_BODY_FORM, true))
                                continue;
                            exp = helper.urlEncode(exp);
                            exp = urlencodeForTomcat(exp);
                            break;
                        case IParameter.PARAM_JSON:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_BODY_JSON, true))
                                continue;
                            break;
                        case IParameter.PARAM_MULTIPART_ATTR:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_BODY_MULTIPART, true))
                                continue;
                            break;
                        case IParameter.PARAM_XML:
                        case IParameter.PARAM_XML_ATTR:
                            if (!Config.getBoolean(Config.ENABLED_FUZZ_BODY_XML, true))
                                continue;
                            break;
                    }
                    if (inHeader) {
                        IParameter newParam = helper.buildParameter(param.getName(), exp, param.getType());
                        tmpRawRequest = helper.updateParameter(rawRequest, newParam);
                    } else {
                        byte[] body = Arrays.copyOfRange(rawRequest, req.getBodyOffset(), rawRequest.length);
                        boolean isJsonNumber = param.getType() == IParameter.PARAM_JSON && body[param.getValueStart() - req.getBodyOffset() - 1] != 34; // ascii:34 = "
                        if (isJsonNumber) {
                            exp = "\"" + exp + "\"";
                        }
                        byte[] newBody = Utils.Replace(body, new int[]{param.getValueStart() - req.getBodyOffset(), param.getValueEnd() - req.getBodyOffset()}, exp.getBytes(StandardCharsets.UTF_8));
                        tmpRawRequest = helper.buildHttpMessage(req.getHeaders(), newBody);
                    }
                    IHttpRequestResponse tmpReq = sendRequest(baseRequestResponse.getHttpService(), tmpRawRequest);
                    domainMap.put(tmpDomain, new ScanItem(param, tmpReq, baseRequestResponse, tmpRawRequest));
                } catch (Exception ex) {
                    parent.stdout.println(ex);
                }
            }
        }
        return domainMap;
    }

    private List<IScanIssue> finalCheck(IHttpRequestResponse baseRequestResponse, IRequestInfo req, Map<String, ScanItem> domainMap) throws InterruptedException {
        List<IScanIssue> issues = new ArrayList<>();
        HttpUtils.waitForRequestFinish(domainMap.size());
        if (backend.supportBatchCheck()) {
            String[] vulPoint = backend.batchCheck(domainMap.keySet().toArray(new String[0]));
            for (String domain : vulPoint) {
                ScanItem item = domainMap.get(domain);
                issues.add(getIssue(baseRequestResponse, req, item));
            }
        } else {
            if (backend.flushCache(domainMap.size())) {
                for (Map.Entry<String, ScanItem> domainItem :
                        domainMap.entrySet()) {
                    ScanItem item = domainItem.getValue();
                    boolean hasIssue = backend.CheckResult(domainItem.getKey());
                    if (hasIssue) {
                        issues.add(getIssue(baseRequestResponse, req, item));
                    } else {
                        item.RequestInfo = req;
                        cacheScanMap.put(domainItem.getKey(), item);
                    }
                }
            } else {
                parent.stdout.println("get backend result failed!\r\n");
            }
        }
        return issues;
    }

    private Log4j2Issue getIssue(IHttpRequestResponse baseRequestResponse, IRequestInfo req, ScanItem item) {
        List<IHttpRequestResponse> requestResponses = new ArrayList<>();
        requestResponses.add(baseRequestResponse);
        String desp = String.format("Vulnerable param is \"%s\" in %s.", item.IsHeader ? item.HeaderName : item.Param.getName(), item.IsHeader ? "Header" : getTypeName(item.Param.getType()));
        if (item.TmpRequest != null) {
            requestResponses.add(item.TmpRequest);
        } else {
            desp += "<br/><br/>RawRequest:<br/><br/><pre>" + new String(item.RawRequest) + "</pre>";
        }
        return new Log4j2Issue(baseRequestResponse.getHttpService(),
                req.getUrl(),
                requestResponses.toArray(new IHttpRequestResponse[0]),
                "Log4j2 RCE Detected",
                desp,
                "High");
    }

    private IHttpRequestResponse sendRequest(IHttpService httpService, byte[] rawRequest) {
        System.out.println(String.format("SET ENABLE_EX_REQUEST %s -> %s",Config.getBoolean(Config.ENABLE_EX_REQUEST, true), httpService.getHost()));
        if (Config.getBoolean(Config.ENABLE_EX_REQUEST, true)) {
            HttpUtils.RawRequest(httpService, rawRequest, parent.helpers.analyzeRequest(httpService, rawRequest));
            return null;
        }
        return parent.callbacks.makeHttpRequest(httpService, rawRequest);
    }

    private String getTypeName(int typeId) {
        switch (typeId) {
            case IParameter.PARAM_URL:
                return "URL";
            case IParameter.PARAM_BODY:
                return "Body";
            case IParameter.PARAM_COOKIE:
                return "Cookie";
            case IParameter.PARAM_JSON:
                return "Body-json";
            case IParameter.PARAM_XML:
                return "Body-xml";
            case IParameter.PARAM_MULTIPART_ATTR:
                return "Body-multipart";
            case IParameter.PARAM_XML_ATTR:
                return "Body-xml-attr";
            default:
                return "unknown";
        }
    }


    @Override
    public int consolidateDuplicateIssues(IScanIssue existingIssue, IScanIssue newIssue) {
        return 0;
    }
}
