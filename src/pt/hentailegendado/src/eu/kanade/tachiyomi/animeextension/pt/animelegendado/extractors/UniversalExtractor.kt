package eu.kanade.tachiyomi.animeextension.pt.hentailegendado.extractors;

import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import aniyomi.lib.playlistutils.PlaylistUtils;
import eu.kanade.tachiyomi.animeextension.BuildConfig;
import eu.kanade.tachiyomi.animesource.model.Track;
import eu.kanade.tachiyomi.animesource.model.Video;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import keiyoushi.utils.ContextKt;
import kotlin.Lazy;
import kotlin.LazyKt;
import kotlin.Metadata;
import kotlin.collections.CollectionsKt;
import kotlin.collections.MapsKt;
import kotlin.collections.SetsKt;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function3;
import kotlin.jvm.internal.DefaultConstructorMarker;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.Ref;
import kotlin.text.CharsKt;
import kotlin.text.Regex;
import kotlin.text.RegexOption;
import kotlin.text.StringsKt;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/* JADX INFO: compiled from: UniversalExtractor.kt */
/* JADX INFO: loaded from: classes5.dex */
@Metadata(d1 = {"\u0000:\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0005\n\u0002\u0010 \n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0003\n\u0002\u0018\u0002\n\u0002\b\u0003\u0018\u0000 \u00182\u00020\u0001:\u0001\u0018B\u000f\u0012\u0006\u0010\u0002\u001a\u00020\u0003¢\u0006\u0004\b\u0004\u0010\u0005J(\u0010\f\u001a\b\u0012\u0004\u0012\u00020\u000e0\r2\u0006\u0010\u000f\u001a\u00020\u00102\u0006\u0010\u0011\u001a\u00020\u00122\b\u0010\u0013\u001a\u0004\u0018\u00010\u0010H\u0007J\f\u0010\u0014\u001a\u00020\u0010*\u00020\u0010H\u0002J\u0014\u0010\u0015\u001a\u00020\u0012*\u00020\u00162\u0006\u0010\u0017\u001a\u00020\u0012H\u0002R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004¢\u0006\u0002\n\u0000R\u001b\u0010\u0006\u001a\u00020\u00078BX\u0082\u0084\u0002¢\u0006\f\n\u0004\b\n\u0010\u000b\u001a\u0004\b\b\u0010\t¨\u0006\u0019"}, d2 = {"Leu/kanade/tachiyomi/animeextension/pt/hentailegendado/extractors/UniversalExtractor;", "", "client", "Lokhttp3/OkHttpClient;", "<init>", "(Lokhttp3/OkHttpClient;)V", "handler", "Landroid/os/Handler;", "getHandler", "()Landroid/os/Handler;", "handler$delegate", "Lkotlin/Lazy;", "videosFromUrl", "", "Leu/kanade/tachiyomi/animesource/model/Video;", "origRequestUrl", "", "origRequestHeader", "Lokhttp3/Headers;", "name", "proper", "toHeaders", "Landroid/webkit/WebResourceRequest;", "baseHeaders", "Companion", "hentailegendado"}, k = BuildConfig.VERSION_CODE, mv = {2, 3, 0}, xi = 48)
public final class UniversalExtractor {
    public static final long TIMEOUT_SEC = 10;
    private final OkHttpClient client;

    /* JADX INFO: renamed from: handler$delegate, reason: from kotlin metadata */
    private final Lazy handler;

    /* JADX INFO: renamed from: Companion, reason: from kotlin metadata */
    public static final Companion INSTANCE = new Companion(null);
    private static final Set<String> FORWARDED_HEADERS = SetsKt.setOf(new String[]{"accept", "origin", "referer", "user-agent"});
    private static final Lazy<Regex> VIDEO_REGEX$delegate = LazyKt.lazy(new Function0() { // from class: eu.kanade.tachiyomi.animeextension.pt.hentailegendado.extractors.UniversalExtractor$$ExternalSyntheticLambda1
        public final Object invoke() {
            return UniversalExtractor.VIDEO_REGEX_delegate$lambda$0();
        }
    });
    private static final Lazy<String> CHECK_SCRIPT$delegate = LazyKt.lazy(new Function0() { // from class: eu.kanade.tachiyomi.animeextension.pt.hentailegendado.extractors.UniversalExtractor$$ExternalSyntheticLambda2
        public final Object invoke() {
            return UniversalExtractor.CHECK_SCRIPT_delegate$lambda$0();
        }
    });

    public UniversalExtractor(OkHttpClient client) {
        Intrinsics.checkNotNullParameter(client, "client");
        this.client = client;
        this.handler = LazyKt.lazy(new Function0() { // from class: eu.kanade.tachiyomi.animeextension.pt.hentailegendado.extractors.UniversalExtractor$$ExternalSyntheticLambda0
            public final Object invoke() {
                return UniversalExtractor.handler_delegate$lambda$0();
            }
        });
    }

    private final Handler getHandler() {
        return (Handler) this.handler.getValue();
    }

    static final Handler handler_delegate$lambda$0() {
        return new Handler(Looper.getMainLooper());
    }

    public final List<Video> videosFromUrl(final String origRequestUrl, final Headers origRequestHeader, String name) {
        Intrinsics.checkNotNullParameter(origRequestUrl, "origRequestUrl");
        Intrinsics.checkNotNullParameter(origRequestHeader, "origRequestHeader");
        HttpUrl httpUrl = HttpUrl.Companion.parse(origRequestUrl);
        if (httpUrl == null) {
            return CollectionsKt.emptyList();
        }
        String host = proper(StringsKt.substringBefore$default(StringsKt.removePrefix(httpUrl.host(), "www."), ".", (String) null, 2, (Object) null));
        final CountDownLatch latch = new CountDownLatch(1);
        final Ref.ObjectRef webView = new Ref.ObjectRef();
        final Ref.ObjectRef resultUrl = new Ref.ObjectRef();
        String str = "";
        resultUrl.element = "";
        final Ref.ObjectRef resultHeaders = new Ref.ObjectRef();
        resultHeaders.element = origRequestHeader;
        Map multimap = origRequestHeader.toMultimap();
        Map linkedHashMap = new LinkedHashMap(MapsKt.mapCapacity(multimap.size()));
        for (Object obj : multimap.entrySet()) {
            Object key = ((Map.Entry) obj).getKey();
            String str2 = str;
            String str3 = (String) CollectionsKt.getOrNull((List) ((Map.Entry) obj).getValue(), 0);
            if (str3 == null) {
                str3 = str2;
            }
            linkedHashMap.put(key, str3);
            str = str2;
        }
        final Map headers = MapsKt.toMutableMap(linkedHashMap);
        getHandler().post(new Runnable() { // from class: eu.kanade.tachiyomi.animeextension.pt.hentailegendado.extractors.UniversalExtractor$$ExternalSyntheticLambda3
            @Override // java.lang.Runnable
            public final void run() {
                UniversalExtractor.videosFromUrl$lambda$1(webView, origRequestUrl, headers, origRequestHeader, resultUrl, resultHeaders, this, latch);
            }
        });
        try {
            latch.await(10L, TimeUnit.SECONDS);
            final String prefix = name == null ? host : name;
            if (StringsKt.contains$default((CharSequence) resultUrl.element, "m3u8", false, 2, (Object) null)) {
                PlaylistUtils playlistUtils = new PlaylistUtils(this.client, (Headers) resultHeaders.element);
                String str4 = (String) resultUrl.element;
                return playlistUtils.extractFromHls(str4, (236 & 2) != 0 ? playlistUtils.toDefaultReferer(str4) : origRequestUrl, (Function2<? super Headers, ? super String, Headers>) ((236 & 4) != 0 ? new PlaylistUtils.C00035(playlistUtils) : null), (Function3<? super Headers, ? super String, ? super String, Headers>) ((236 & 8) != 0 ? new Function3() { // from class: aniyomi.lib.playlistutils.PlaylistUtils$$ExternalSyntheticLambda21
                    public final Object invoke(Object obj2, Object obj3, Object obj4) {
                        return PlaylistUtils.extractFromHls$lambda$4(playlistUtils, (Headers) obj2, (String) obj3, (String) obj4);
                    }
                } : null), (Function1<? super String, String>) ((236 & 16) != 0 ? new Function1() { // from class: aniyomi.lib.playlistutils.PlaylistUtils$$ExternalSyntheticLambda22
                    public final Object invoke(Object obj2) {
                        return PlaylistUtils.extractFromHls$lambda$5((String) obj2);
                    }
                } : new Function1() { // from class: eu.kanade.tachiyomi.animeextension.pt.hentailegendado.extractors.UniversalExtractor$$ExternalSyntheticLambda5
                    public final Object invoke(Object obj2) {
                        return UniversalExtractor.videosFromUrl$lambda$3(prefix, (String) obj2);
                    }
                }), (List<Track>) ((236 & 32) != 0 ? CollectionsKt.emptyList() : null), (List<Track>) ((236 & 64) != 0 ? CollectionsKt.emptyList() : null), (Function1<? super String, String>) ((236 & 128) != 0 ? new Function1() { // from class: aniyomi.lib.playlistutils.PlaylistUtils$$ExternalSyntheticLambda23
                    public final Object invoke(Object obj2) {
                        return PlaylistUtils.extractFromHls$lambda$6(playlistUtils, (String) obj2);
                    }
                } : null));
            }
            final String prefix2 = prefix;
            if (StringsKt.contains$default((CharSequence) resultUrl.element, "mpd", false, 2, (Object) null)) {
                PlaylistUtils playlistUtils2 = new PlaylistUtils(this.client, (Headers) resultHeaders.element);
                String str5 = (String) resultUrl.element;
                return playlistUtils2.extractFromDash(str5, (Function1<? super String, String>) new Function1() { // from class: eu.kanade.tachiyomi.animeextension.pt.hentailegendado.extractors.UniversalExtractor$$ExternalSyntheticLambda6
                    public final Object invoke(Object obj2) {
                        return UniversalExtractor.videosFromUrl$lambda$4(prefix2, (String) obj2);
                    }
                }, (248 & 4) != 0 ? playlistUtils2.toDefaultReferer(str5) : origRequestUrl, (Function2<? super Headers, ? super String, Headers>) ((248 & 8) != 0 ? new PlaylistUtils.AnonymousClass5(playlistUtils2) : null), (Function3<? super Headers, ? super String, ? super String, Headers>) ((248 & 16) != 0 ? new Function3() { // from class: aniyomi.lib.playlistutils.PlaylistUtils$$ExternalSyntheticLambda24
                    public final Object invoke(Object obj2, Object obj3, Object obj4) {
                        return PlaylistUtils.extractFromDash$lambda$4(playlistUtils2, (Headers) obj2, (String) obj3, (String) obj4);
                    }
                } : null), (List<Track>) ((248 & 32) != 0 ? CollectionsKt.emptyList() : null), (List<Track>) ((248 & 64) != 0 ? CollectionsKt.emptyList() : null), (Function1<? super String, String>) ((248 & 128) != 0 ? new Function1() { // from class: aniyomi.lib.playlistutils.PlaylistUtils$$ExternalSyntheticLambda25
                    public final Object invoke(Object obj2) {
                        return PlaylistUtils.extractFromDash$lambda$5(playlistUtils2, (String) obj2);
                    }
                } : null));
            }
            if (StringsKt.contains$default((CharSequence) resultUrl.element, "mp4", false, 2, (Object) null)) {
                return CollectionsKt.listOf(new Video((String) resultUrl.element, prefix2 + ": MP4", (String) resultUrl.element, (Headers) resultHeaders.element, (List) null, (List) null, 48, (DefaultConstructorMarker) null));
            }
            return CollectionsKt.emptyList();
        } finally {
            getHandler().post(new Runnable() { // from class: eu.kanade.tachiyomi.animeextension.pt.hentailegendado.extractors.UniversalExtractor$$ExternalSyntheticLambda4
                @Override // java.lang.Runnable
                public final void run() {
                    UniversalExtractor.videosFromUrl$lambda$2(webView);
                }
            });
        }
    }

    static final void videosFromUrl$lambda$1(Ref.ObjectRef $webView, String $origRequestUrl, Map $headers, Headers $origRequestHeader, Ref.ObjectRef $resultUrl, Ref.ObjectRef $resultHeaders, UniversalExtractor this$0, CountDownLatch $latch) {
        WebView newView = new WebView(ContextKt.getApplicationContext());
        $webView.element = newView;
        WebSettings settings = newView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUserAgentString($origRequestHeader.get("User-Agent"));
        newView.setWebViewClient(new UniversalExtractor$videosFromUrl$1$2($resultUrl, $resultHeaders, this$0, $origRequestHeader, $latch));
        WebView webView = (WebView) $webView.element;
        if (webView != null) {
            webView.loadUrl($origRequestUrl, $headers);
        }
    }

    static final void videosFromUrl$lambda$2(Ref.ObjectRef $webView) {
        WebView webView = (WebView) $webView.element;
        if (webView != null) {
            webView.stopLoading();
        }
        WebView webView2 = (WebView) $webView.element;
        if (webView2 != null) {
            webView2.destroy();
        }
        $webView.element = null;
    }

    static final String videosFromUrl$lambda$3(String $prefix, String it) {
        Intrinsics.checkNotNullParameter(it, "it");
        return $prefix + ": " + it;
    }

    static final String videosFromUrl$lambda$4(String $prefix, String it) {
        Intrinsics.checkNotNullParameter(it, "it");
        return $prefix + ": " + it;
    }

    private final String proper(String $this$proper) {
        String strValueOf;
        if (!($this$proper.length() > 0)) {
            return $this$proper;
        }
        StringBuilder sb = new StringBuilder();
        char cCharAt = $this$proper.charAt(0);
        if (Character.isLowerCase(cCharAt)) {
            Locale locale = Locale.getDefault();
            Intrinsics.checkNotNullExpressionValue(locale, "getDefault(...)");
            strValueOf = CharsKt.titlecase(cCharAt, locale);
        } else {
            strValueOf = String.valueOf(cCharAt);
        }
        StringBuilder sbAppend = sb.append((Object) strValueOf);
        String strSubstring = $this$proper.substring(1);
        Intrinsics.checkNotNullExpressionValue(strSubstring, "substring(...)");
        return sbAppend.append(strSubstring).toString();
    }

    /* JADX INFO: Access modifiers changed from: private */
    public final Headers toHeaders(WebResourceRequest $this$toHeaders, Headers baseHeaders) {
        String lowerCase;
        Headers.Builder builderNewBuilder = baseHeaders.newBuilder();
        Map<String, String> requestHeaders = $this$toHeaders.getRequestHeaders();
        Intrinsics.checkNotNullExpressionValue(requestHeaders, "getRequestHeaders(...)");
        Iterator<Map.Entry<String, String>> it = requestHeaders.entrySet().iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            Map.Entry<String, String> next = it.next();
            String key = next.getKey();
            String value = next.getValue();
            Set<String> set = FORWARDED_HEADERS;
            if (key != null) {
                lowerCase = key.toLowerCase(Locale.ROOT);
                Intrinsics.checkNotNullExpressionValue(lowerCase, "toLowerCase(...)");
            }
            if (CollectionsKt.contains(set, lowerCase)) {
                Intrinsics.checkNotNull(key);
                Intrinsics.checkNotNull(value);
                builderNewBuilder.set(key, value);
            }
        }
        String cookie = CookieManager.getInstance().getCookie($this$toHeaders.getUrl().toString());
        if (cookie != null) {
            lowerCase = StringsKt.isBlank(cookie) ? null : cookie;
            if (lowerCase != null) {
                builderNewBuilder.set("Cookie", lowerCase);
            }
        }
        return builderNewBuilder.build();
    }

    /* JADX INFO: compiled from: UniversalExtractor.kt */
    @Metadata(d1 = {"\u0000$\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0010\t\n\u0000\n\u0002\u0010\"\n\u0002\u0010\u000e\n\u0000\n\u0002\u0018\u0002\n\u0002\b\t\b\u0086\u0003\u0018\u00002\u00020\u0001B\t\b\u0002¢\u0006\u0004\b\u0002\u0010\u0003R\u000e\u0010\u0004\u001a\u00020\u0005X\u0086T¢\u0006\u0002\n\u0000R\u0014\u0010\u0006\u001a\b\u0012\u0004\u0012\u00020\b0\u0007X\u0082\u0004¢\u0006\u0002\n\u0000R\u001b\u0010\t\u001a\u00020\n8BX\u0082\u0084\u0002¢\u0006\f\n\u0004\b\r\u0010\u000e\u001a\u0004\b\u000b\u0010\fR\u001b\u0010\u000f\u001a\u00020\b8BX\u0082\u0084\u0002¢\u0006\f\n\u0004\b\u0012\u0010\u000e\u001a\u0004\b\u0010\u0010\u0011¨\u0006\u0013"}, d2 = {"Leu/kanade/tachiyomi/animeextension/pt/hentailegendado/extractors/UniversalExtractor$Companion;", "", "<init>", "()V", "TIMEOUT_SEC", "", "FORWARDED_HEADERS", "", "", "VIDEO_REGEX", "Lkotlin/text/Regex;", "getVIDEO_REGEX", "()Lkotlin/text/Regex;", "VIDEO_REGEX$delegate", "Lkotlin/Lazy;", "CHECK_SCRIPT", "getCHECK_SCRIPT", "()Ljava/lang/String;", "CHECK_SCRIPT$delegate", "hentailegendado"}, k = BuildConfig.VERSION_CODE, mv = {2, 3, 0}, xi = 48)
    public static final class Companion {
        public /* synthetic */ Companion(DefaultConstructorMarker defaultConstructorMarker) {
            this();
        }

        private Companion() {
        }

        /* JADX INFO: Access modifiers changed from: private */
        public final Regex getVIDEO_REGEX() {
            return (Regex) UniversalExtractor.VIDEO_REGEX$delegate.getValue();
        }

        /* JADX INFO: Access modifiers changed from: private */
        public final String getCHECK_SCRIPT() {
            return (String) UniversalExtractor.CHECK_SCRIPT$delegate.getValue();
        }
    }

    static final Regex VIDEO_REGEX_delegate$lambda$0() {
        return new Regex(".*\\.(mp4|m3u8|mpd)(\\?.*)?$", RegexOption.IGNORE_CASE);
    }

    static final String CHECK_SCRIPT_delegate$lambda$0() {
        return "setInterval(() => {\n    var playButton = document.getElementById('player-button-container')\n    if (playButton) {\n        playButton.click()\n    }\n    var downloadButton = document.querySelector(\".downloader-button\")\n    if (downloadButton) {\n        if (downloadButton.href) {\n            location.href = downloadButton.href\n        } else {\n            downloadButton.click()\n        }\n    }\n    // Default jwplayer instance\n    try { jwplayer(0).play(); } catch {}\n}, 2500)";
    }
}
