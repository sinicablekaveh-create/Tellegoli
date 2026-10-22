package ir.electricconnect.nativefree;

import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class TelegramBrowserTest {
    private TdlibEngine engine;
    private FakeSession session;
    private Browser listener;

    @Before public void setup(){
        engine=new TdlibEngine(RuntimeEnvironment.getApplication());
        session=new FakeSession();
        listener=new Browser();
        ReflectionHelpers.setField(engine,"client",session);
        engine.attachBrowser(listener);
    }
    private void update(TdApi.Object object) {
        ReflectionHelpers.callInstanceMethod(engine,"handleUpdate",
            ReflectionHelpers.ClassParameter.from(TdApi.Object.class,object));
    }
    private void drain(){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1300));}
    private void ready(){update(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateReady()));}
    private static TdApi.ChatPosition position(TdApi.ChatList list,long order){
        TdApi.ChatPosition p=new TdApi.ChatPosition();
        p.list=list;p.order=order;return p;
    }
    private static TdApi.Chat chat(long id,String title,TdApi.ChatType type,TdApi.ChatList list){
        TdApi.Chat c=new TdApi.Chat();c.id=id;c.title=title;c.type=type;
        c.positions=new TdApi.ChatPosition[]{position(list,100)};
        return c;
    }

    @Test public void displaysPrivateGroupChannelAndArchiveButNoLoggedOutChats(){
        update(new TdApi.UpdateNewChat(chat(1,"کابل",new TdApi.ChatTypePrivate(),new TdApi.ChatListMain())));
        update(new TdApi.UpdateNewChat(chat(2,"گروه برق",new TdApi.ChatTypeBasicGroup(),new TdApi.ChatListArchive())));
        TdApi.ChatTypeSupergroup channel=new TdApi.ChatTypeSupergroup();channel.isChannel=true;
        update(new TdApi.UpdateNewChat(chat(3,"کانال اخبار",channel,new TdApi.ChatListMain())));
        drain();assertTrue(listener.chats.isEmpty());
        ready();drain();
        assertEquals(3,listener.chats.size());
        assertTrue(listener.chats.stream().anyMatch(c->c.name.equals("کابل")&&c.category.equals("شخصی")));
        assertTrue(listener.chats.stream().anyMatch(c->c.name.equals("گروه برق")&&c.archived));
        assertTrue(listener.chats.stream().anyMatch(c->c.name.equals("کانال اخبار")&&c.category.equals("کانال")));
        update(new TdApi.UpdateChatTitle(1,"شخصی جدید"));drain();
        assertTrue(listener.chats.stream().anyMatch(c->c.name.equals("شخصی جدید")));
        update(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateLoggingOut()));
        drain();assertTrue(listener.chats.isEmpty());
    }

    @Test public void publicDiscoveryFiltersPrivateAndChannelHits(){
        ready();
        engine.searchPublicGroups("تابلو برق",listener);
        Client.ResultHandler request=session.take(TdApi.SearchPublicChats.class);
        TdApi.Chats found=new TdApi.Chats();found.chatIds=new long[]{11,12,13};
        request.onResult(found);
        session.take(TdApi.GetChat.class).onResult(
            chat(11,"تابلو سازان",new TdApi.ChatTypeBasicGroup(),new TdApi.ChatListMain()));
        TdApi.ChatTypeSupergroup channel=new TdApi.ChatTypeSupergroup();
        channel.isChannel=true;
        session.take(TdApi.GetChat.class).onResult(chat(12,"کانال تبلیغات",channel,new TdApi.ChatListMain()));
        session.take(TdApi.GetChat.class).onResult(
            chat(13,"نام شخصی",new TdApi.ChatTypePrivate(),new TdApi.ChatListMain()));
        drain();assertEquals(1,listener.publicGroups.size());
        assertEquals("تابلو سازان",listener.publicGroups.get(0).name);
    }

    @Test public void onDemandSearchIsBoundedAndNoAutoMessagesAreSent(){
        ready();
        engine.searchAllMessages("سینی کابل",listener);
        TdApi.SearchMessages call=session.takeFunction(TdApi.SearchMessages.class);
        assertEquals("سینی کابل",call.query);
        assertEquals(30,call.limit);
        assertFalse(session.calls.stream().anyMatch(c->c.function instanceof TdApi.SendMessage));
        TdApi.FoundMessages result=new TdApi.FoundMessages();
        TdApi.Message msg=new TdApi.Message();
        msg.id=9;msg.chatId=123;msg.date=100;
        TdApi.MessageText txt=new TdApi.MessageText();
        txt.text=new TdApi.FormattedText("پست عمومی کابل",null);
        msg.content=txt;
        result.messages=new TdApi.Message[]{msg};
        session.callback.onResult(result);
        drain();
        assertEquals(1,listener.messages.size());
        assertEquals("پست عمومی کابل",listener.messages.get(0).body);
    }

    @Test public void globalSearchContinuesWithTDLibOffsetToken(){
        ready();
        class Paginated extends Browser {
            String token="";
            int pages;
            @Override public void onGlobalMessagesPage(String query,
                 List<TdlibEngine.MessagePreview> rows,String error,String next){
                pages++;
                token=next;
                messages=rows;
            }
        }
        Paginated pagination=new Paginated();
        engine.attachBrowser(pagination);
        engine.searchAllMessages("کابل",pagination);
        TdApi.SearchMessages first=session.takeFunction(TdApi.SearchMessages.class);
        assertEquals("",first.offset);
        TdApi.FoundMessages firstResult=new TdApi.FoundMessages();
        firstResult.nextOffset="next-unique-page-token";
        firstResult.messages=new TdApi.Message[0];
        session.callback.onResult(firstResult);
        drain();
        assertEquals("next-unique-page-token",pagination.token);
        engine.searchAllMessages("کابل",pagination.token,pagination);
        TdApi.SearchMessages second=session.takeFunction(TdApi.SearchMessages.class);
        assertEquals("next-unique-page-token",second.offset);
        TdApi.FoundMessages lastResult=new TdApi.FoundMessages();
        lastResult.nextOffset="";
        lastResult.messages=new TdApi.Message[0];
        session.callback.onResult(lastResult);
        drain();
        assertEquals(2,pagination.pages);
        assertEquals("",pagination.token);
    }

    @Test public void readyDoesNotStartDiscoveryWithoutUserAction() {
        ReflectionHelpers.setStaticField(TdlibEngine.class,"shared",engine);
        try {
            ready();drain();
            try(var controller=Robolectric.buildActivity(TelegramBrowserActivity.class).setup()) {
                drain();assertFalse(session.calls.stream().anyMatch(x->x.function instanceof TdApi.SearchPublicChats));
            }
        } finally {ReflectionHelpers.setStaticField(TdlibEngine.class,"shared",null);}
    }

    @Test public void browserScreenExposesAllChatActions(){
        try(var controller=Robolectric.buildActivity(TelegramBrowserActivity.class).create()){
            android.view.View view=controller.get().getWindow().getDecorView();
            assertNotNull(find(view,"شروع جست‌وجوی خودکار چندعبارتی گروه‌ها"));
            assertNotNull(find(view,"خروجی اکسل"));
            assertNotNull(find(view,"جست‌وجو در پیام‌های همهٔ چت‌ها"));
            assertNotNull(find(view,"بازخوانی همهٔ چت‌های حساب"));
        }
    }
    @Test public void historyAppendsDeduplicatesAndMoreActionsUseRealContinuation() {
        ReflectionHelpers.setStaticField(TdlibEngine.class,"shared",engine);
        ReflectionHelpers.setField(engine,"loggedIn",true);
        try(var controller=Robolectric.buildActivity(TelegramBrowserActivity.class).create()) {
            TelegramBrowserActivity activity=controller.get();engine.attachBrowser(activity);
            ReflectionHelpers.setField(activity,"chosenChat",5L);
            TdlibEngine.MessagePreview a=new TdlibEngine.MessagePreview(5,100,"گروه","اول",1);
            TdlibEngine.MessagePreview b=new TdlibEngine.MessagePreview(5,90,"گروه","دوم",1);
            TdlibEngine.MessagePreview c=new TdlibEngine.MessagePreview(5,80,"گروه","سوم",1);
            activity.onChatHistory(5,List.of(a,b),null);activity.onChatHistory(5,List.of(b,c),null);
            assertEquals(3,(int)ReflectionHelpers.getField(activity,"totalHistoryMessages"));
            android.view.View screen=activity.getWindow().getDecorView();assertNotNull(find(screen,"اول"));assertNotNull(find(screen,"سوم"));
            find(screen,"نمایش پیام‌های قدیمی‌تر").performClick();assertEquals(80,session.takeFunction(TdApi.GetChatHistory.class).fromMessageId);
            ReflectionHelpers.setField(activity,"currentMessageQuery","کابل");
            activity.onGlobalMessagesPage("کابل",List.of(a),null,"token-1");
            // An overlapping/empty page with a valid next token must remain continuable.
            activity.onGlobalMessagesPage("کابل",List.of(a),null,"token-2");
            assertTrue(find(screen,"نمایش پیام‌های بیشتر").isEnabled());find(screen,"نمایش پیام‌های بیشتر").performClick();
            assertEquals("token-2",session.takeFunction(TdApi.SearchMessages.class).offset);
        } finally {ReflectionHelpers.setStaticField(TdlibEngine.class,"shared",null);}
    }

    private static android.widget.TextView find(android.view.View view,String text){
        if(view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView)view).getText()))
            return (android.widget.TextView)view;
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup v=(android.view.ViewGroup)view;
            for(int i=0;i<v.getChildCount();i++){
                android.widget.TextView t=find(v.getChildAt(i),text);
                if(t!=null)return t;
            }
        }
        return null;
    }

    static class Call {
        final TdApi.Function<?> function;final Client.ResultHandler callback;
        Call(TdApi.Function<?> f,Client.ResultHandler h){function=f;callback=h;}
    }
    static class FakeSession implements TdlibEngine.Session {
        final List<Call> calls=new ArrayList<>();
        Client.ResultHandler callback;
        public void send(TdApi.Function<?> request,Client.ResultHandler handler){
            calls.add(new Call(request,handler));
        }
        Client.ResultHandler take(Class<?> type) {
            for(int i=0;i<calls.size();i++){
                if(type.isInstance(calls.get(i).function)){
                    callback=calls.remove(i).callback;
                    return callback;
                }
            }
            throw new AssertionError("Missing request "+type.getSimpleName());
        }
        <T> T takeFunction(Class<T> type){
            for(Call c:calls)if(type.isInstance(c.function)){
                callback=c.callback;calls.remove(c);return type.cast(c.function);
            }
            throw new AssertionError("Missing request "+type.getSimpleName());
        }
    }
    static class Browser implements TdlibEngine.BrowserCallback {
        List<TdlibEngine.ChatOverview> chats=new ArrayList<>();
        List<TdlibEngine.ChatOverview> publicGroups=new ArrayList<>();
        List<TdlibEngine.MessagePreview> messages=new ArrayList<>();
        public void onBrowserStatus(String s,String d){}
        public void onChatIndex(List<TdlibEngine.ChatOverview> value){chats=value;}
        public void onPublicGroups(String q,List<TdlibEngine.ChatOverview> value,String e){publicGroups=value;}
        public void onGlobalMessages(String q,List<TdlibEngine.MessagePreview> value,String e){messages=value;}
        public void onChatHistory(long id,List<TdlibEngine.MessagePreview> value,String e){}
    }
}
