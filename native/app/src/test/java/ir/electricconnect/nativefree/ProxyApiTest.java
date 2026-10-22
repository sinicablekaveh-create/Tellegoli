package ir.electricconnect.nativefree;

import android.os.Looper;
import org.drinkless.tdlib.TdApi;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class ProxyApiTest {
    @Test public void socksAndMtprotoUsePinnedApiBeforeAndAfterReadyAndHandleErrors() {
        TdlibEngine engine=new TdlibEngine(RuntimeEnvironment.getApplication());
        AtomicReference<TdApi.Function<?>> called=new AtomicReference<>();AtomicReference<TdApi.Object> reply=new AtomicReference<>(new TdApi.AddedProxy());
        TdlibEngine.Session session=(function,handler)->{called.set(function);handler.onResult(reply.get());};
        ReflectionHelpers.setField(engine,"client",session);AtomicReference<Boolean> success=new AtomicReference<>();
        assertFalse(engine.isReady());
        engine.setTelegramProxyLink("tg://socks?server=proxy.example.com&port=1080&user=local&pass=private",(ok,detail)->success.set(ok));
        shadowOf(Looper.getMainLooper()).idle();assertTrue(success.get());
        TdApi.AddProxy add=(TdApi.AddProxy)called.get();assertTrue(add.enable);assertEquals(1080,add.proxy.port);assertTrue(add.proxy.type instanceof TdApi.ProxyTypeSocks5);
        ReflectionHelpers.setField(engine,"loggedIn",true);
        engine.setTelegramProxyLink("https://t.me/proxy?server=proxy.example.com&port=443&secret=0123456789abcdef0123456789abcdef",(ok,detail)->success.set(ok));
        shadowOf(Looper.getMainLooper()).idle();assertTrue(success.get());assertTrue(((TdApi.AddProxy)called.get()).proxy.type instanceof TdApi.ProxyTypeMtproto);
        reply.set(new TdApi.Error(400,"Do not display credentials"));
        engine.setTelegramProxyLink("tg://socks?server=proxy.example.com&port=1080",(ok,detail)->{success.set(ok);assertFalse(detail.contains("credentials"));});
        shadowOf(Looper.getMainLooper()).idle();assertFalse(success.get());
        reply.set(new TdApi.Ok());engine.disableTelegramProxy((ok,detail)->success.set(ok));shadowOf(Looper.getMainLooper()).idle();assertTrue(success.get());assertTrue(called.get() instanceof TdApi.DisableProxy);
    }
    @Test public void base64UrlLinkReachesTdlibAsHexadecimal() {
        TdlibEngine engine=new TdlibEngine(RuntimeEnvironment.getApplication());
        AtomicReference<TdApi.Function<?>> called=new AtomicReference<>();
        ReflectionHelpers.setField(engine,"client",
            (TdlibEngine.Session)(function,handler)->{
                called.set(function);
                handler.onResult(new TdApi.AddedProxy());
            });
        AtomicReference<Boolean> success=new AtomicReference<>(false);
        engine.setTelegramProxyLink(
            "https://t.me/proxy?server=proxy.example.com&port=8880&secret=eeAAAAAAAAAAAAAAAAAAAA",
            (ok,detail)->success.set(ok));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(success.get());
        TdApi.AddProxy request=(TdApi.AddProxy)called.get();
        assertNotNull(request);
        assertEquals("proxy.example.com",request.proxy.server);
        assertEquals(8880,request.proxy.port);
        assertTrue(request.proxy.type instanceof TdApi.ProxyTypeMtproto);
        assertEquals("79e00000000000000000000000000000",
            ((TdApi.ProxyTypeMtproto)request.proxy.type).secret);
    }

    @Test public void malformedSecretIsRejectedBeforeAnyTdlibRequest() {
        TdlibEngine engine=new TdlibEngine(RuntimeEnvironment.getApplication());
        ReflectionHelpers.setField(engine,"client",(TdlibEngine.Session)(f,h)->fail("Malformed link must not send a request"));
        engine.setTelegramProxyLink("tg://proxy?server=proxy.example.com&port=443&secret=bad",(ok,detail)->assertFalse(ok));shadowOf(Looper.getMainLooper()).idle();
    }
}
