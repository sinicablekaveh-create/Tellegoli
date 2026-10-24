package ir.electricconnect.nativefree;

import android.os.Looper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, manifest=Config.NONE)
public class TdlibEngineTest {
    private TdlibEngine engine;
    private FakeSession session;
    private Capture capture;
    private FakeCredentialStore credentials;

    @Before public void setup() {
        credentials=new FakeCredentialStore();
        engine=new TdlibEngine(RuntimeEnvironment.getApplication(),credentials);
        session=new FakeSession();
        capture=new Capture();
        ReflectionHelpers.setField(engine,"client",session);
        engine.attach(capture);
    }

    @Test public void missingCredentialsCanBeSavedAfterTdlibRequestsParameters() {
        waitForParameters();
        drain();
        assertEquals("NEED_API",capture.state);
        assertTrue(session.calls.isEmpty());

        engine.saveAppCredentials(1,testHash());
        assertEquals(1,session.calls.size());
        assertEquals(1,((TdApi.SetTdlibParameters)session.calls.get(0).function).apiId);
        assertTrue(engine.hasCredentials());
    }

    @Test public void rejectedCredentialsCanBeCorrectedWithoutRestartAndAreNotEchoed() {
        credentials.value="1:"+testHash();
        waitForParameters();
        session.take(TdApi.SetTdlibParameters.class)
                .onResult(new TdApi.Error(400,"Rejected "+testHash()));
        drain();
        assertEquals("CONFIG_ERROR",capture.state);
        assertFalse(capture.resultDetail.contains(testHash()));

        engine.saveAppCredentials(2,testHash());
        assertEquals(1,session.calls.size());
        assertEquals(2,((TdApi.SetTdlibParameters)session.calls.get(0).function).apiId);
    }

    @Test public void changedCredentialsWaitForPendingRequestAndRetryOnlyItsFailure() {
        credentials.value="1:"+testHash();
        waitForParameters();
        Client.ResultHandler original=session.take(TdApi.SetTdlibParameters.class);
        waitForParameters();
        engine.saveAppCredentials(2,testHash());
        assertTrue(session.calls.isEmpty());

        original.onResult(new TdApi.Error(400,"Invalid API credentials"));
        assertEquals(1,session.calls.size());
        assertEquals(2,((TdApi.SetTdlibParameters)session.calls.get(0).function).apiId);
        session.take(TdApi.SetTdlibParameters.class).onResult(new TdApi.Ok());
        waitForParameters();
        assertTrue(session.calls.isEmpty());

        update(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateWaitPhoneNumber()));
        engine.saveAppCredentials(3,testHash());
        assertTrue(session.calls.isEmpty());
        drain();
        assertEquals("PHONE",capture.state);
    }

    @Test public void synchronousAuthorizationAdvanceIsNotOverwrittenByConfiguration() {
        credentials.value="1:"+testHash();
        session.onSend=function->{
            if(function instanceof TdApi.SetTdlibParameters)
                update(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateWaitPhoneNumber()));
        };
        waitForParameters();
        Client.ResultHandler old=session.take(TdApi.SetTdlibParameters.class);
        old.onResult(new TdApi.Error(400,"Late error"));
        drain();
        assertEquals("PHONE",capture.state);
    }

    @Test public void cachedChatsSurviveReadyAndTrackArchiveMovesAndRemoval() {
        update(new TdApi.UpdateNewChat(chat(1,"کیان",new TdApi.ChatListMain())));
        drain();
        assertTrue(capture.chats.isEmpty());
        ready();drain();
        assertEquals(1,capture.chats.size());
        update(new TdApi.UpdateChatPosition(1,position(new TdApi.ChatListArchive(),10)));
        update(new TdApi.UpdateChatPosition(1,position(new TdApi.ChatListMain(),0)));
        drain();assertEquals(1,capture.chats.size());
        update(new TdApi.UpdateChatTitle(1,"نام جدید"));
        drain();assertEquals("نام جدید",capture.chats.get(0).name);
        update(new TdApi.UpdateChatPosition(1,position(new TdApi.ChatListArchive(),0)));
        drain();assertTrue(capture.chats.isEmpty());
    }

    @Test public void refreshIgnoresOldContactAndChatPageCallbacks() {
        ready();
        Client.ResultHandler firstContacts=session.take(TdApi.GetContacts.class);
        firstContacts.onResult(new TdApi.Users(1,new long[]{1}));
        Client.ResultHandler firstUser=session.take(TdApi.GetUser.class);
        Client.ResultHandler firstPage=session.take(TdApi.LoadChats.class);
        engine.refresh();
        int requests=session.calls.size();
        firstPage.onResult(new TdApi.Ok());
        assertEquals(requests,session.calls.size());
        firstUser.onResult(user(1,"Old"));
        Client.ResultHandler current=session.take(TdApi.GetContacts.class);
        current.onResult(new TdApi.Users(1,new long[]{2}));
        session.take(TdApi.GetUser.class).onResult(user(2,"Current"));
        drain();
        assertEquals(List.of("Current"),capture.contacts);
    }

    @Test public void loadsBothListsUntilExhaustedAndStopsAfterLogout() {
        ready();
        Client.ResultHandler main=session.take(TdApi.LoadChats.class);
        Client.ResultHandler archive=session.take(TdApi.LoadChats.class);
        main.onResult(new TdApi.Ok());
        session.take(TdApi.LoadChats.class).onResult(new TdApi.Error(404,"All chats loaded"));
        archive.onResult(new TdApi.Error(404,"All chats loaded"));
        assertFalse(session.calls.stream().anyMatch(c->c.function instanceof TdApi.LoadChats));
        update(new TdApi.UpdateNewChat(chat(1,"Group",new TdApi.ChatListArchive())));
        Client.ResultHandler contacts=session.take(TdApi.GetContacts.class);
        update(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateLoggingOut()));
        contacts.onResult(new TdApi.Users(1,new long[]{2}));
        update(new TdApi.UpdateNewChat(chat(2,"Late",new TdApi.ChatListMain())));
        drain();
        assertTrue(capture.chats.isEmpty());
        assertTrue(capture.contacts.isEmpty());
        assertFalse(session.calls.stream().anyMatch(c->c.function instanceof TdApi.GetUser));
    }

    private void ready(){update(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateReady()));}
    private void waitForParameters(){update(new TdApi.UpdateAuthorizationState(new TdApi.AuthorizationStateWaitTdlibParameters()));}
    private static String testHash(){return "0123456789abcdef0123456789abcdef";}
    private void update(TdApi.Object value) {
        ReflectionHelpers.callInstanceMethod(engine,"handleUpdate",ReflectionHelpers.ClassParameter.from(TdApi.Object.class,value));
    }
    private void drain(){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(101));}
    private static TdApi.Chat chat(long id,String name,TdApi.ChatList list) {
        TdApi.Chat c=new TdApi.Chat();c.id=id;c.title=name;
        c.type=new TdApi.ChatTypeBasicGroup();c.positions=new TdApi.ChatPosition[]{position(list,10)};
        return c;
    }
    private static TdApi.ChatPosition position(TdApi.ChatList list,long order) {
        TdApi.ChatPosition p=new TdApi.ChatPosition();p.list=list;p.order=order;return p;
    }
    private static TdApi.User user(long id,String name) {
        TdApi.User u=new TdApi.User();u.id=id;u.firstName=name;u.lastName="";return u;
    }
    private static class Call {
        final TdApi.Function<?> function; final Client.ResultHandler callback;
        Call(TdApi.Function<?> f,Client.ResultHandler c){function=f;callback=c;}
    }
    private static class FakeSession implements TdlibEngine.Session {
        final List<Call> calls=new ArrayList<>();
        Consumer<TdApi.Function<?>> onSend;
        public void send(TdApi.Function<?> f,Client.ResultHandler c){
            calls.add(new Call(f,c));
            if(onSend!=null)onSend.accept(f);
        }
        Client.ResultHandler take(Class<?> type) {
            for(int i=0;i<calls.size();i++)if(type.isInstance(calls.get(i).function))return calls.remove(i).callback;
            throw new AssertionError("Missing request "+type.getSimpleName());
        }
    }
    private static class FakeCredentialStore implements TdlibEngine.CredentialStore {
        String value;
        public boolean has(String field){return value!=null;}
        public void put(String field,String data){value=data;}
        public String get(String field){return value;}
    }
    private static class Capture implements TdlibEngine.Listener {
        String state="",resultDetail="";
        List<TdlibEngine.ChatInfo> chats=new ArrayList<>();
        List<String> contacts=new ArrayList<>();
        public void onState(String s,String d){state=s;}
        public void onChats(List<TdlibEngine.ChatInfo> c){chats=c;}
        public void onContacts(List<String> c){contacts=c;}
        public void onResult(String t,boolean ok,String d){resultDetail=d;}
    }
}
