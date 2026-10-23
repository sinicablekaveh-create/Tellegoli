package ir.electricconnect.nativefree;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Personal Telegram session using real TDLib Java + native JNI.
 * All sending is explicitly initiated by the user, never automated.
 * No private contacts/chats are sent to the Exa search API.
 */
final class TdlibEngine {
    interface Listener {
        void onState(String state, String detail);
        void onChats(List<ChatInfo> chats);
        void onContacts(List<String> contacts);
        void onResult(String task, boolean ok, String detail);
    }

    static final class ChatInfo {
        final long id;
        final String name;
        ChatInfo(long id, String name) { this.id = id; this.name = name; }
    }

    /** Safe display-only metadata; never exported to Exa or the contacts table. */
    static final class ChatOverview {
        final long id;
        final String name;
        final String category;
        final boolean archived;
        ChatOverview(long id,String name,String category,boolean archived) {
            this.id=id;this.name=name;this.category=category;this.archived=archived;
        }
    }
    static final class MessagePreview {
        final long chatId, messageId;
        final String chatTitle, body;
        final int timestamp;
        MessagePreview(long chatId,long messageId,String chatTitle,String body,int timestamp) {
            this.chatId=chatId;this.messageId=messageId;
            this.chatTitle=chatTitle;this.body=body;this.timestamp=timestamp;
        }
    }
    interface BrowserCallback {
        void onBrowserStatus(String state,String detail);
        void onChatIndex(List<ChatOverview> chats);
        default void onChatLoading(int loaded,boolean mainFinished,boolean archiveFinished,String error) {}
        void onPublicGroups(String query,List<ChatOverview> groups,String error);
        void onGlobalMessages(String query,List<MessagePreview> messages,String error);
        /** Optional next-page callback: existing listeners still receive the old one. */
        default void onGlobalMessagesPage(String query,List<MessagePreview> messages,
                                          String error,String nextOffset) {
            onGlobalMessages(query,messages,error);
        }
        void onChatHistory(long chatId,List<MessagePreview> messages,String error);
    }

    private static volatile TdlibEngine shared;
    static TdlibEngine get(Context context) {
        if (shared == null) {
            synchronized (TdlibEngine.class) {
                if (shared == null) shared = new TdlibEngine(context.getApplicationContext());
            }
        }
        return shared;
    }
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Vault vault;
    private final Map<Long,ChatInfo> groups = new HashMap<>();
    private final Map<Long,ChatOverview> allKnownChats = new HashMap<>();
    private final Map<Long,Integer> allChatLists = new HashMap<>();
    // Metadata from the authorized TDLib session; never exported as phone contacts.
    private final Map<Long,TdApi.User> accountUsers = new HashMap<>();
    private final Map<Long,Long> privateChatUsers = new HashMap<>();
    private final Map<Long,Long> supergroupChats = new HashMap<>();
    private final Map<Long,String> groupPublicLinks = new HashMap<>();
    // Folder positions can exist even when a chat is not in Main/Archive.
    private final Map<Long,java.util.Set<Integer>> chatFolders = new HashMap<>();
    private volatile BrowserCallback browser;
    private boolean allChatsPublishQueued;
    private final Map<Long,String> contacts = new HashMap<>();
    private int refreshGeneration;
    private int globalSearchGeneration,historyGeneration;
    private boolean mainChatsFinished,archiveChatsFinished;
    private String chatLoadingError="";
    private boolean groupsPublishQueued, contactsPublishQueued;
    interface Session {
        void send(TdApi.Function<?> function, Client.ResultHandler handler);
    }
    private Session client;
    private int sessionGeneration;
    private final Map<Long,ChatInfo> knownGroups = new HashMap<>();
    private final Map<Long,Integer> groupLists = new HashMap<>();
    private volatile Listener listener;
    private String state = "DISCONNECTED";
    private String status = "حساب شخصی متصل نیست.";
    private boolean loggedIn;
    private long lastAttempt;

    TdlibEngine(Context context) {
        app = context;
        vault = new Vault(context);
        lastAttempt = context.getSharedPreferences("tdlib_controls", Context.MODE_PRIVATE).getLong("last_attempt",0);
    }

    boolean hasCredentials() { return vault.has("app_credentials"); }

    void attach(Listener newListener) {
        listener = newListener;
        updateStatus();
        publishGroups();
        publishContacts();
    }
    void detach(Listener oldListener) { if (listener == oldListener) listener = null; }

    void attachBrowser(BrowserCallback callback) {
        browser=callback;
        main.post(()->{
            if(browser==callback)callback.onBrowserStatus(state,status);
        });
        publishAllChats();
    }
    void detachBrowser(BrowserCallback callback) {
        if(browser==callback)browser=null;
    }

    private synchronized void publishAllChats() {
        if(allChatsPublishQueued)return;
        allChatsPublishQueued=true;
        main.postDelayed(()->{
            ArrayList<ChatOverview> rows=new ArrayList<>();
            synchronized(TdlibEngine.this) {
                allChatsPublishQueued=false;
                if(loggedIn)for(ChatOverview info:allKnownChats.values()) {
                    if(allChatLists.getOrDefault(info.id,0)!=0) {
                        rows.add(new ChatOverview(info.id,info.name,info.category,
                                (allChatLists.getOrDefault(info.id,0)&2)!=0 &&
                                (allChatLists.getOrDefault(info.id,0)&1)==0));
                    }
                }
            }
            Collections.sort(rows,Comparator
                .comparingInt((ChatOverview a)->a.name==null||a.name.trim().isEmpty()?1:0)
                .thenComparing(a->a.name==null?"":a.name,String.CASE_INSENSITIVE_ORDER));
            BrowserCallback target=browser;
            if(target!=null)target.onChatIndex(rows);
        },100);
    }
    private void browseError(BrowserCallback target,String message) {
        main.post(()->{
            if(browser==target)target.onBrowserStatus("ERROR",message);
        });
    }

    private void result(String task, boolean success, String detail) {
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onResult(task,success,detail);
        });
    }
    private synchronized void state(String s, String detail) {
        boolean becameReady="READY".equals(s)&&!"READY".equals(state);
        state = s;status = detail;notifyAll();updateStatus();
        if(becameReady&&app instanceof DiscoveryApplication)
            main.post(()->((DiscoveryApplication)app).telegramReady());
    }
    private void updateStatus() {
        final String currentState = state, currentStatus = status;
        main.post(()->{
            Listener l=listener;
            if(l!=null)l.onState(currentState,currentStatus);
            BrowserCallback b=browser;
            if(b!=null)b.onBrowserStatus(currentState,currentStatus);
        });
    }
    private synchronized void publishGroups() {
        if(groupsPublishQueued)return;
        groupsPublishQueued=true;
        main.postDelayed(()->{
            ArrayList<ChatInfo> list;
            synchronized(TdlibEngine.this) {
                groupsPublishQueued=false;
                list=new ArrayList<>(groups.values());
            }
            Collections.sort(list, Comparator.comparing(o->o.name));
            Listener l=listener;if(l!=null)l.onChats(list);
        },100);
    }
    private synchronized void publishContacts() {
        if(contactsPublishQueued)return;
        contactsPublishQueued=true;
        main.postDelayed(()->{
            ArrayList<String> copy;
            synchronized(TdlibEngine.this) {
                contactsPublishQueued=false;
                copy=new ArrayList<>(contacts.values());
            }
            Collections.sort(copy, String.CASE_INSENSITIVE_ORDER);
            Listener l=listener;if(l!=null)l.onContacts(copy);
        },100);
    }

    synchronized void saveAppCredentials(int id, String hash) {
        if(id < 1 || hash==null || !hash.matches("[0-9a-fA-F]{32}")) {
            result("API",false,"api_id باید عدد مثبت و api_hash باید ۳۲ نویسهٔ هگز باشد.");
            return;
        }
        try {
            vault.put("app_credentials", id+":"+hash);
        }catch(Exception e){result("API",false,"ذخیرهٔ رمزگذاری‌شدهٔ API انجام نشد.");return;}
        result("API",true,"کلیدهای برنامه فقط روی همین گوشی ذخیره شدند.");
        connect();
        // TDLib will send WaitTdlibParameters update and use stored keys.
    }

    synchronized void connect() {
        if(client!=null){updateStatus();return;}
        if(!hasCredentials()) {
            state("NEED_API","api_id و api_hash را از my.telegram.org در گوشی وارد کنید.");
            return;
        }
        try {
            System.loadLibrary("tdjni");
            Client.execute(new TdApi.SetLogVerbosityLevel(0));
            final int generation=++sessionGeneration;
            Client nativeClient=Client.create(obj->{
                synchronized(TdlibEngine.this) {
                    if(generation==sessionGeneration)handleUpdate(obj);
                }
            },null,null);
            client=nativeClient::send;
            state("CONNECTING","TDLib شروع شد؛ در حال دریافت وضعیت ورود...");
        }catch(Throwable e) {
            client=null;
            state("NATIVE_ERROR","TDLib بومی روی این دستگاه بارگذاری نشد؛ ABI یا نسخهٔ کتابخانه را بررسی کنید.");
        }
    }

    private synchronized void configure() {
        if(client==null)return;
        try {
            String keys = vault.get("app_credentials");
            if(keys==null){state("NEED_API","اطلاعات API ثبت نشده است.");return;}
            int index=keys.indexOf(':');
            if(index<1) throw new IllegalStateException("Bad credentials");
            TdApi.SetTdlibParameters params=new TdApi.SetTdlibParameters();
            params.apiId=Integer.parseInt(keys.substring(0,index));
            params.apiHash=keys.substring(index+1);
            params.databaseDirectory=app.getFilesDir().getAbsolutePath()+"/tdlib-account-db";
            params.filesDirectory=app.getFilesDir().getAbsolutePath()+"/tdlib-files";
            params.useMessageDatabase=true;
            params.useFileDatabase=false;
            params.useChatInfoDatabase=true;
            params.useSecretChats=false;
            params.systemLanguageCode="fa";
            params.deviceModel=Build.MODEL;
            params.applicationVersion=BuildConfig.VERSION_NAME;
            client.send(params, obj->respond("تنظیم TDLib",obj));
            state("CONFIGURING","پارامترهای رسمی TDLib تنظیم شدند.");
        }catch(Exception e){state("CONFIG_ERROR","خطا در خواندن یا تنظیم شناسه‌های API.");}
    }

    private synchronized void handleUpdate(TdApi.Object obj) {
        try {
            if(obj instanceof TdApi.UpdateAuthorizationState) {
                onAuthorization(((TdApi.UpdateAuthorizationState)obj).authorizationState);
            } else if(obj instanceof TdApi.UpdateNewChat) {
                addChat(((TdApi.UpdateNewChat)obj).chat);
            } else if(obj instanceof TdApi.UpdateUser) {
                TdApi.User person=((TdApi.UpdateUser)obj).user;
                if(person!=null) {
                    accountUsers.put(person.id,person);
                    for(Map.Entry<Long,Long> entry:privateChatUsers.entrySet()) {
                        if(entry.getValue()!=person.id)continue;
                        ChatOverview old=allKnownChats.get(entry.getKey());
                        if(old==null)continue;
                        String title=bestUserTitle(person,old.name);
                        String kind=person.type instanceof TdApi.UserTypeBot?"ربات":"شخصی";
                        if(!title.equals(old.name)||!kind.equals(old.category))
                            allKnownChats.put(old.id,new ChatOverview(old.id,title,kind,old.archived));
                    }
                    publishAllChats();
                }
            } else if(obj instanceof TdApi.UpdateSupergroup) {
                TdApi.Supergroup group=((TdApi.UpdateSupergroup)obj).supergroup;
                if(group!=null)rememberGroupLink(group);
            } else if(obj instanceof TdApi.UpdateChatTitle) {
                TdApi.UpdateChatTitle u=(TdApi.UpdateChatTitle)obj;
                ChatOverview original=allKnownChats.get(u.chatId);
                if(original!=null) {
                    Long userId=privateChatUsers.get(u.chatId);
                    TdApi.User person=userId==null?null:accountUsers.get(userId);
                    String title=person==null?u.title:bestUserTitle(person,u.title);
                    allKnownChats.put(u.chatId,new ChatOverview(u.chatId,title,
                            original.category,original.archived));
                    publishAllChats();
                }
                if(knownGroups.containsKey(u.chatId)) {
                    knownGroups.put(u.chatId,new ChatInfo(u.chatId,u.title));
                    updateGroup(u.chatId);
                }
            } else if(obj instanceof TdApi.UpdateChatPosition) {
                TdApi.UpdateChatPosition u=(TdApi.UpdateChatPosition)obj;
                updatePosition(u.chatId,u.position);
                updateGroup(u.chatId);
            } else if(obj instanceof TdApi.UpdateMessageSendSucceeded) {
                TdApi.UpdateMessageSendSucceeded update=(TdApi.UpdateMessageSendSucceeded)obj;
                ScheduledQueue.recordDelivery(app,update.message.chatId,update.oldMessageId,true);
                result("ارسال",true,"تلگرام موفقیت ارسال پیام را اعلام کرد.");
            } else if(obj instanceof TdApi.UpdateMessageSendFailed) {
                TdApi.UpdateMessageSendFailed update=(TdApi.UpdateMessageSendFailed)obj;
                ScheduledQueue.recordDelivery(app,update.message.chatId,update.oldMessageId,false);
                result("ارسال",false,"تلگرام ارسال پیام را ناموفق اعلام کرد؛ دوباره خودکار تلاش نمی‌شود.");
            }
        }catch(Exception e){
            result("TDLib",false,"پردازش پاسخ تلگرام دچار خطا شد.");
        }
    }

    private synchronized void onAuthorization(TdApi.AuthorizationState auth) {
        if(auth instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            configure();
        } else if(auth instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            loggedIn=false;
            state("PHONE","شمارهٔ حساب شخصی تلگرام خودتان را همراه کد کشور وارد کنید.");
        } else if(auth instanceof TdApi.AuthorizationStateWaitCode) {
            state("CODE","کد ورود تلگرام را فقط داخل برنامه وارد کنید.");
        } else if(auth instanceof TdApi.AuthorizationStateWaitEmailAddress) {
            state("EMAIL","برای ادامهٔ ورود، ایمیل تأیید را وارد کنید.");
        } else if(auth instanceof TdApi.AuthorizationStateWaitEmailCode) {
            state("EMAIL_CODE","کد تأیید ایمیل را داخل گوشی وارد کنید.");
        } else if(auth instanceof TdApi.AuthorizationStateWaitPassword) {
            state("PASSWORD","رمز دومرحله‌ای تلگرام را وارد کنید؛ این رمز ذخیره نمی‌شود.");
        } else if(auth instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation) {
            String link=((TdApi.AuthorizationStateWaitOtherDeviceConfirmation)auth).link;
            state("OTHER_DEVICE","پیوند تأیید ورود را در دستگاه دیگری که از قبل به تلگرام وارد شده است باز کنید:\n"+link);
        } else if(auth instanceof TdApi.AuthorizationStateWaitRegistration) {
            state("REGISTER","این شماره هنوز حساب ندارد؛ برای ثبت‌نام نام و نام خانوادگی لازم است.");
        } else if(auth instanceof TdApi.AuthorizationStateReady) {
            loggedIn=true;
            for(long id:knownGroups.keySet())updateGroup(id);
            publishAllChats();
            state("READY","حساب شخصی تلگرام متصل شد. می‌توانید گروه‌ها و مخاطبان را بررسی کنید.");
            refresh();
        } else if(auth instanceof TdApi.AuthorizationStateLoggingOut) {
            loggedIn=false;
            clearAccountLists();
            DiscoverySchedule.disable(app);DiscoveryCoordinator.get(app).stop();ScheduledQueue.pause(app,"پس از ورود به حساب، صف را دوباره بررسی کنید.");
            state("LOGGING_OUT","در حال خروج امن از حساب...");
        } else if(auth instanceof TdApi.AuthorizationStateClosing) {
            loggedIn=false;
            clearAccountLists();
            state("CLOSING","نشست TDLib در حال بسته‌شدن است.");
        } else if(auth instanceof TdApi.AuthorizationStateClosed) {
            loggedIn=false;
            client=null;
            clearAccountLists();
            state("CLOSED","نشست بسته شد. برای اتصال مجدد گزینهٔ اتصال را بزنید.");
        }
    }

    private void respond(String task, TdApi.Object response) {
        if(response instanceof TdApi.Error) {
            TdApi.Error e=(TdApi.Error)response;
            String description=e.message==null?"خطای نامشخص":e.message;
            result(task,false,"خطای تلگرام "+e.code+": "+redact(description));
        } else {
            result(task,true,"درخواست پذیرفته شد.");
        }
    }
    private String redact(String raw) {
        return raw.length()>200 ? raw.substring(0,200) : raw;
    }

    synchronized void enterPhone(String phone) {
        if(phone==null || !phone.matches("\\+[0-9]{8,15}")) {
            result("شماره",false,"شماره باید با + و کد کشور باشد.");return;
        }
        request("شماره",new TdApi.SetAuthenticationPhoneNumber(phone,null));
    }
    synchronized void enterCode(String code) {
        if(code==null || !code.matches("[0-9]{3,10}")){result("کد",false,"کد عددی را وارد کنید.");return;}
        request("کد ورود",new TdApi.CheckAuthenticationCode(code));
    }
    synchronized void enterEmail(String address) {
        if(address==null || !address.contains("@") || address.length()>254) {
            result("ایمیل",false,"ایمیل معتبر نیست.");return;
        }
        request("ایمیل",new TdApi.SetAuthenticationEmailAddress(address));
    }
    synchronized void enterEmailCode(String code) {
        if(code==null||code.length()<3||code.length()>64){result("ایمیل",false,"کد ایمیل معتبر نیست.");return;}
        request("کد ایمیل",new TdApi.CheckAuthenticationEmailCode(new TdApi.EmailAddressAuthenticationCode(code)));
    }
    synchronized void enterPassword(String password) {
        if(password==null||password.isEmpty()){result("رمز",false,"رمز دومرحله‌ای را وارد کنید.");return;}
        request("رمز دومرحله‌ای",new TdApi.CheckAuthenticationPassword(password));
    }
    synchronized void register(String first, String last) {
        if(first==null||first.trim().isEmpty()){result("ثبت‌نام",false,"نام لازم است.");return;}
        request("ثبت‌نام",new TdApi.RegisterUser(first.trim(),last==null?"":last.trim(),false));
    }
    private synchronized void request(String name,TdApi.Function<?> function) {
        if(client==null){result(name,false,"TDLib به حساب وصل نیست.");return;}
        client.send(function,o->respond(name,o));
    }
    synchronized void refresh() {
        if(!loggedIn||client==null){result("فهرست",false,"اول وارد حساب شوید.");return;}
        final Session session=client;
        final int generation=++refreshGeneration;
        mainChatsFinished=false;archiveChatsFinished=false;chatLoadingError="";publishChatLoading();
        contacts.clear();
        publishContacts();
        // Continue until TDLib returns 404 (all chats loaded), including archived chats.
        loadChats(session,generation,new TdApi.ChatListMain());
        loadChats(session,generation,new TdApi.ChatListArchive());
        session.send(new TdApi.GetContacts(),o->{
            synchronized(TdlibEngine.this) {
            if(!currentRefresh(session,generation))return;
            if(o instanceof TdApi.Users) {
                TdApi.Users users=(TdApi.Users)o;
                for(long userId:users.userIds) {
                    session.send(new TdApi.GetUser(userId),u->{
                        synchronized(TdlibEngine.this) {
                        if(!currentRefresh(session,generation))return;
                        if(u instanceof TdApi.User) {
                            TdApi.User user=(TdApi.User)u;
                            String name=(user.firstName+" "+user.lastName).trim();
                            if(!name.isEmpty())contacts.put(userId,name);
                            publishContacts();
                        }
                        }
                    });
                }
            } else respond("مخاطبان",o);
            }
        });
        session.send(new TdApi.GetMe(),o->{
            synchronized(TdlibEngine.this) {
            if(!currentRefresh(session,generation))return;
            if(o instanceof TdApi.User){
                TdApi.User u=(TdApi.User)o;
                SharedPreferences account=app.getSharedPreferences("discovery",Context.MODE_PRIVATE);
                long previous=account.getLong("account_id",0);
                if(previous!=0&&previous!=u.id) {
                    DiscoverySchedule.disable(app);DiscoveryCoordinator.get(app).stop();ScheduledQueue.pause(app,"حساب تغییر کرده؛ مقصدها را دوباره تأیید کنید.");
                }
                account.edit().putLong("account_id",u.id).apply();
                result("حساب",true,"حساب: "+(u.firstName+" "+u.lastName).trim());
            }else respond("حساب",o);
            }
        });
        publishGroups();
    }

    private synchronized boolean currentRefresh(Session session,int generation) {
        return loggedIn && client==session && refreshGeneration==generation;
    }

    private void loadChats(Session session,int generation,TdApi.ChatList list) {
        session.send(new TdApi.LoadChats(list,100),response->{
            synchronized(TdlibEngine.this) {
                if(!currentRefresh(session,generation))return;
                if(response instanceof TdApi.Ok) {
                    publishChatLoading();
                    loadChats(session,generation,list);
                } else if(response instanceof TdApi.Error && ((TdApi.Error)response).code==404) {
                    if(list instanceof TdApi.ChatListMain)mainChatsFinished=true;else archiveChatsFinished=true;
                    publishGroups();publishAllChats();publishChatLoading();
                } else {chatLoadingError="بارگذاری فهرست قطع شد؛ برای تلاش دوباره بازخوانی را بزنید.";publishChatLoading();respond("گروه‌ها",response);}
            }
        });
    }

    private void publishChatLoading() {
        final BrowserCallback callback=browser;final int count=(int)allChatLists.values().stream().filter(v->v!=0).count();
        final boolean m=mainChatsFinished,a=archiveChatsFinished;final String error=chatLoadingError;
        main.post(()->{if(callback!=null&&browser==callback)callback.onChatLoading(count,m,a,error);});
    }
    private synchronized void clearAccountLists() {
        refreshGeneration++;
        groups.clear();
        allKnownChats.clear();
        allChatLists.clear();
        accountUsers.clear();
        privateChatUsers.clear();
        supergroupChats.clear();
        groupPublicLinks.clear();
        chatFolders.clear();
        publishAllChats();
        knownGroups.clear();
        groupLists.clear();
        contacts.clear();
        publishGroups();
        publishContacts();
    }
    private static boolean publicGroupType(TdApi.ChatType type) {
        return type instanceof TdApi.ChatTypeBasicGroup
                || (type instanceof TdApi.ChatTypeSupergroup
                && !((TdApi.ChatTypeSupergroup)type).isChannel);
    }

    private static String category(TdApi.ChatType type) {
        if(type instanceof TdApi.ChatTypeBasicGroup)return "گروه";
        if(type instanceof TdApi.ChatTypeSupergroup)
            return ((TdApi.ChatTypeSupergroup)type).isChannel?"کانال":"گروه";
        if(type instanceof TdApi.ChatTypePrivate)return "شخصی";
        if(type instanceof TdApi.ChatTypeSecret)return "گفت‌وگوی محرمانه";
        return "سایر";
    }

    private static String bestUserTitle(TdApi.User person,String fallback) {
        String first=person.firstName==null?"":person.firstName;
        String last=person.lastName==null?"":person.lastName;
        String label=(first+" "+last).trim();
        return label.isEmpty()?(fallback==null?"":fallback):label;
    }

    private synchronized void rememberGroupLink(TdApi.Supergroup group) {
        if(group==null)return;
        String username="";
        if(group.usernames!=null && group.usernames.activeUsernames!=null
                && group.usernames.activeUsernames.length>0)
            username=group.usernames.activeUsernames[0];
        if(username==null || !username.matches("[A-Za-z0-9_]{5,32}"))return;
        for(Map.Entry<Long,Long> entry:supergroupChats.entrySet())
            if(entry.getValue()==group.id)
                groupPublicLinks.put(entry.getKey(),"https://t.me/"+username);
    }
    synchronized String publicGroupLink(long chatId) {
        return groupPublicLinks.getOrDefault(chatId,"");
    }

    private synchronized void addChat(TdApi.Chat chat) {
        if(chat==null || "LOGGING_OUT".equals(state) || "CLOSING".equals(state) || "CLOSED".equals(state))return;
        String name=chat.title==null?"":chat.title;
        String kind=category(chat.type);
        if(chat.type instanceof TdApi.ChatTypePrivate) {
            long userId=((TdApi.ChatTypePrivate)chat.type).userId;
            privateChatUsers.put(chat.id,userId);
            TdApi.User person=accountUsers.get(userId);
            if(person!=null) {
                name=bestUserTitle(person,name);
                if(person.type instanceof TdApi.UserTypeBot)kind="ربات";
            }
        } else if(chat.type instanceof TdApi.ChatTypeSupergroup) {
            long superId=((TdApi.ChatTypeSupergroup)chat.type).supergroupId;
            supergroupChats.put(chat.id,superId);
            if(client!=null)client.send(new TdApi.GetSupergroup(superId),response->{
                synchronized(TdlibEngine.this) {
                    if(response instanceof TdApi.Supergroup)rememberGroupLink((TdApi.Supergroup)response);
                }
            });
        }
        allKnownChats.put(chat.id,new ChatOverview(chat.id,name,kind,false));
        allChatLists.putIfAbsent(chat.id,0);
        if(chat.positions!=null)for(TdApi.ChatPosition position:chat.positions)
            updatePosition(chat.id,position);
        publishAllChats();
        if(publicGroupType(chat.type)) {
            // TDLib can deliver cached chats before AuthorizationStateReady.
            knownGroups.put(chat.id,new ChatInfo(chat.id,chat.title));
            groupLists.putIfAbsent(chat.id,0);
            if(chat.positions!=null)for(TdApi.ChatPosition position:chat.positions)updatePosition(chat.id,position);
            updateGroup(chat.id);
        }
    }

    private void updatePosition(long chatId,TdApi.ChatPosition position) {
        if(position==null)return;
        int bit=position.list instanceof TdApi.ChatListMain?1:
            position.list instanceof TdApi.ChatListArchive?2:
            position.list instanceof TdApi.ChatListFolder?4:0;
        if(bit==0)return;
        if(bit==4) {
            int folderId=((TdApi.ChatListFolder)position.list).chatFolderId;
            java.util.Set<Integer> folders=chatFolders.get(chatId);
            if(folders==null){folders=new java.util.HashSet<>();chatFolders.put(chatId,folders);}
            if(position.order==0)folders.remove(folderId);else folders.add(folderId);
            if(folders.isEmpty())chatFolders.remove(chatId);
        }
        if(allKnownChats.containsKey(chatId)) {
            int lists=allChatLists.getOrDefault(chatId,0);
            if(bit!=4)lists=position.order==0?lists & ~bit:lists | bit;
            allChatLists.put(chatId,(lists & ~4) | (chatFolders.containsKey(chatId)?4:0));
            publishAllChats();
        }
        if(!knownGroups.containsKey(chatId))return;
        int lists=groupLists.getOrDefault(chatId,0);
        if(bit!=4)lists=position.order==0?lists & ~bit:lists | bit;
        groupLists.put(chatId,(lists & ~4) | (chatFolders.containsKey(chatId)?4:0));
    }

    private void updateGroup(long id) {
        if(loggedIn && groupLists.getOrDefault(id,0)!=0)groups.put(id,knownGroups.get(id));
        else groups.remove(id);
        publishGroups();
    }

    /** Searches public Telegram groups by their public title/username.
     * Results are displayed only, never joined or contacted automatically.
     */
    /** Snapshot of account groups currently loaded by TDLib (main and archive). */
    synchronized List<ChatInfo> snapshotJoinedGroups() {
        ArrayList<ChatInfo> result=new ArrayList<>(groups.values());
        result.sort(Comparator.comparing(item->item.name,String.CASE_INSENSITIVE_ORDER));
        return result;
    }
    synchronized List<ChatInfo> accountGroups() {return new ArrayList<>(groups.values());}
    synchronized boolean isReady() {return loggedIn&&client!=null;}
    synchronized String connectionState(){return state;}
    interface PublicGroupsCallback {void completed(List<ChatOverview> rows,String error);}
    void searchPublicGroups(String term,BrowserCallback requester) {
        searchPublicGroups(term,(rows,error)->{
            if(browser==requester)requester.onPublicGroups(term,rows,error);
        },()->browser==requester);
    }
    void searchPublicGroups(String term,PublicGroupsCallback callback,java.util.function.BooleanSupplier activeRun) {
        String query=term==null?"":term.trim();
        if(query.length()<3||query.length()>80) {
            main.post(()->callback.completed(Collections.emptyList(),"عبارت جست‌وجوی گروه باید بین ۳ تا ۸۰ نویسه باشد."));return;
        }
        final Session active;final int generation;
        synchronized(this) {active=client;generation=sessionGeneration;}
        if(!isReady()) {main.post(()->callback.completed(Collections.emptyList(),"ابتدا وارد حساب تلگرام شوید."));return;}
        TdApi.SearchPublicChats search=new TdApi.SearchPublicChats();search.query=query;
        active.send(search,obj->{
            if(!activeRun.getAsBoolean())return;
            synchronized(this){if(client!=active||sessionGeneration!=generation||!loggedIn)return;}
            if(!(obj instanceof TdApi.Chats)) {
                final String detail;
                if(obj instanceof TdApi.Error) {
                    TdApi.Error issue=(TdApi.Error)obj;
                    recordRateLimit(issue);
                    detail="خطای جست‌وجوی عمومی تلگرام "+issue.code+": "+redact(issue.message==null?"نامشخص":issue.message);
                } else detail="پاسخ غیرمنتظره از جست‌وجوی عمومی تلگرام.";
                main.post(()->{if(activeRun.getAsBoolean())callback.completed(Collections.emptyList(),detail);});
                return;
            }
            long[] ids=((TdApi.Chats)obj).chatIds;
            if(ids==null||ids.length==0) {main.post(()->{if(activeRun.getAsBoolean())callback.completed(Collections.emptyList(),null);});return;}
            // Do not silently discard server-provided results after forty matches.
            final int total=ids.length;
            final Map<Long,ChatOverview> found=new HashMap<>();final int[] pending={total};
            for(int i=0;i<total&&activeRun.getAsBoolean();i++) {
                active.send(new TdApi.GetChat(ids[i]),reply->{
                    synchronized(found) {
                        if(reply instanceof TdApi.Chat) {
                            TdApi.Chat chat=(TdApi.Chat)reply;
                            if(publicGroupType(chat.type))found.put(chat.id,new ChatOverview(chat.id,chat.title,"گروه عمومی",false));
                        }
                        if(--pending[0]==0) {
                            ArrayList<ChatOverview> snapshot=new ArrayList<>(found.values());
                            Collections.sort(snapshot,Comparator.comparing(c->c.name));
                            main.post(()->{
                                synchronized(TdlibEngine.this){if(client!=active||sessionGeneration!=generation||!loggedIn)return;}
                                if(activeRun.getAsBoolean())callback.completed(snapshot,null);
                            });
                        }
                    }
                });
            }
        });
    }

    /** Search across the signed-in account's available, non-secret messages.
     * This is an explicit foreground request, bounded to 30 results and is
     * never persisted, exported, or used to harvest phone numbers.
     */
    void searchAllMessages(String term,BrowserCallback requester) {
        searchAllMessages(term,"",requester);
    }
    /** TDLib search page continuation token; bounded, user-driven, no export. */
    void searchAllMessages(String term,String pageOffset,BrowserCallback requester) {
        String query=term==null?"":term.trim();
        if(query.length()<2 || query.length()>120) {
            browseError(requester,"عبارت جست‌وجوی پیام باید بین ۲ تا ۱۲۰ نویسه باشد.");return;
        }
        final Session active;final int generation;
        synchronized(this) {
            if(!loggedIn||client==null) {
                browseError(requester,"برای جست‌وجو در پیام‌ها باید وارد تلگرام شوید.");return;
            }
            active=client;
            if(pageOffset==null||pageOffset.isEmpty())globalSearchGeneration++;
            generation=globalSearchGeneration;
        }
        TdApi.SearchMessages req=new TdApi.SearchMessages();
        req.query=query;
        req.offset=pageOffset==null?"":pageOffset;
        req.limit=30;
        active.send(req,obj->{
            ArrayList<MessagePreview> messages=new ArrayList<>();
            String error=null;
            String continuation="";
            if(obj instanceof TdApi.FoundMessages) {
                TdApi.FoundMessages found=(TdApi.FoundMessages)obj;
                TdApi.Message[] items=found.messages;
                if(items!=null)for(TdApi.Message message:items)messages.add(preview(message));
                continuation=found.nextOffset==null?"":found.nextOffset;
            } else {
                error="جست‌وجوی پیام‌ها ناموفق بود؛ وضعیت حساب تلگرام را بررسی کنید.";
            }
            final String resultError=error;
            final String nextPage=continuation;
            main.post(()->{
                synchronized(TdlibEngine.this) {
                    if(browser!=requester||client!=active||!loggedIn||generation!=globalSearchGeneration)return;
                }
                requester.onGlobalMessagesPage(query,messages,resultError,nextPage);
            });
        });
    }

    interface ProxyCallback {
        void completed(boolean ok,String detail);
    }
    /** Only accepts a Telegram proxy share link. No credential ever goes to Exa. */
    private static final class TelegramProxyConfig {
        final String server, username, password, secret;
        final int port;
        final boolean mtproto;
        TelegramProxyConfig(String server,int port,boolean mtproto,
                            String user,String password,String secret) {
            this.server=server;this.port=port;this.mtproto=mtproto;
            this.username=user;this.password=password;this.secret=secret;
        }
    }

    /**
     * TDLib expects the MTProto proxy secret in hexadecimal form.
     * Telegram share links may encode the same bytes as unpadded Base64URL;
     * e.g. a 22-character string encodes the standard 16-byte secret.
     * Never print or persist the secret during normalization.
     */
    private static String normalizeMtprotoSecret(String secret) {
        if(secret==null || secret.isEmpty() || secret.length()>512)return null;
        // Leave already valid hex secrets unchanged (16 bytes, dd + 16,
        // or ee + 16 + at least one byte of domain information).
        if(secret.matches("(?i)[0-9a-f]{32}") ||
           secret.matches("(?i)dd[0-9a-f]{32}") ||
           secret.matches("(?i)ee[0-9a-f]{34,510}"))return secret;

        // Reject mixed alphabets, whitespace, and oversized strings before
        // decoding; an opaque string starting with textual "ee" may still be
        // Base64URL rather than the 0xee hexadecimal proxy marker.
        if(secret.length()>344)return null;
        boolean urlSafe=secret.matches("[A-Za-z0-9_-]+={0,2}");
        boolean standard=secret.matches("[A-Za-z0-9+/]+={0,2}");
        if(!urlSafe && !standard)return null;
        try {
            java.util.Base64.Decoder decoder=urlSafe
                ?java.util.Base64.getUrlDecoder():java.util.Base64.getDecoder();
            byte[] bytes=decoder.decode(secret);
            String canonical=(urlSafe
                ?java.util.Base64.getUrlEncoder()
                :java.util.Base64.getEncoder())
                .withoutPadding().encodeToString(bytes);
            if(!canonical.equals(secret.replaceFirst("=+$","")))return null;
            // Plain MTProto: 16 bytes. Padded: dd + 16 bytes.
            // TLS-imitating: ee + 16 bytes + nonempty domain bytes.
            int prefix=bytes.length==0?-1:(bytes[0]&0xff);
            if(!(bytes.length==16 ||
                 (bytes.length==17 && prefix==0xdd) ||
                 (bytes.length>=18 && bytes.length<=256 && prefix==0xee)))
                return null;
            final char[] hexDigits="0123456789abcdef".toCharArray();
            char[] result=new char[bytes.length*2];
            for(int i=0;i<bytes.length;i++) {
                int b=bytes[i]&0xff;
                result[i*2]=hexDigits[b>>>4];
                result[i*2+1]=hexDigits[b&15];
            }
            return new String(result);
        } catch(IllegalArgumentException ignored) {
            return null;
        }
    }

    private static TelegramProxyConfig parseTelegramProxy(String link) {
        if(link==null || link.length()>2048)return null;
        try {
            android.net.Uri uri=android.net.Uri.parse(link.trim());
            String scheme=uri.getScheme(),host=uri.getHost(),path=uri.getPath();
            if(scheme==null || host==null)return null;
            boolean telegramScheme="tg".equalsIgnoreCase(scheme);
            if(telegramScheme) {
                if(!host.equalsIgnoreCase("proxy")&&!host.equalsIgnoreCase("socks"))
                    return null;
            }else if(("https".equalsIgnoreCase(scheme)) &&
                    (host.equalsIgnoreCase("t.me") || host.equalsIgnoreCase("telegram.me"))) {
                if(path==null || (!path.equalsIgnoreCase("/proxy") &&
                        !path.equalsIgnoreCase("/socks")))return null;
            }else return null;
            boolean mtproto=telegramScheme
                ? host.equalsIgnoreCase("proxy"):path.equalsIgnoreCase("/proxy");
            String server=uri.getQueryParameter("server");
            String rawPort=uri.getQueryParameter("port");
            if(server==null||server.isEmpty()||server.length()>253
                ||server.contains("/")||server.contains("@")||server.contains(" ")
                ||rawPort==null || !rawPort.matches("[0-9]{1,5}"))return null;
            if(!server.matches("[a-zA-Z0-9.:-]+"))return null;
            int port=Integer.parseInt(rawPort);
            if(port<1 || port>65535)return null;
            String user=uri.getQueryParameter("user");
            String pass=uri.getQueryParameter("pass");
            String secret=uri.getQueryParameter("secret");
            if(mtproto) {
                secret=normalizeMtprotoSecret(secret);
                if(secret==null)return null;
            }
            if(user!=null && user.length()>256 || pass!=null && pass.length()>256)return null;
            return new TelegramProxyConfig(server,port,mtproto,
                    user==null?"":user,pass==null?"":pass,secret==null?"":secret);
        }catch(Exception e){return null;}
    }

    void setTelegramProxyLink(String link,ProxyCallback callback) {
        TelegramProxyConfig config=parseTelegramProxy(link);
        if(config==null){
            main.post(()->callback.completed(false,"لینک پروکسی تلگرام معتبر نیست."));
            return;
        }
        final Session active;
        synchronized(this){
            if(client==null)connect();
            active=client;
        }
        if(active==null){
            main.post(()->callback.completed(false,
                "ابتدا api_id و api_hash را در صفحهٔ اتصال تلگرام ثبت کن."));
            return;
        }
        TdApi.Proxy proxy=new TdApi.Proxy();
        proxy.server=config.server;
        proxy.port=config.port;
        proxy.type=config.mtproto
            ? new TdApi.ProxyTypeMtproto(config.secret)
            : new TdApi.ProxyTypeSocks5(config.username,config.password);
        TdApi.AddProxy add=new TdApi.AddProxy();
        add.proxy=proxy;
        add.enable=true;add.comment="";
        active.send(add,reply->{
            boolean ok=reply instanceof TdApi.AddedProxy;
            main.post(()->callback.completed(ok,ok
                ?"پروکسی تلگرام فعال شد."
                :"فعال‌سازی پروکسی ناموفق بود؛ نشانی و دسترسی را بررسی کن."));
        });
    }
    void disableTelegramProxy(ProxyCallback callback){
        final Session active;
        synchronized(this){active=client;}
        if(active==null){
            main.post(()->callback.completed(false,"تلگرام هنوز متصل نشده است."));
            return;
        }
        active.send(new TdApi.DisableProxy(),reply->{
            boolean ok=reply instanceof TdApi.Ok;
            main.post(()->callback.completed(ok,ok
                ?"پروکسی غیرفعال شد؛ ارتباط مستقیم برقرار خواهد شد."
                :"قطع پروکسی انجام نشد."));
        });
    }

    /** Fetch a small visible page of a selected accessible chat on demand. */
    void readChatHistory(long chatId,long fromMessageId,BrowserCallback requester) {
        final Session active;final int generation;
        synchronized(this) {
            if(!loggedIn||client==null) {
                browseError(requester,"برای نمایش چت وارد حساب تلگرام شوید.");return;
            }
            active=client;if(fromMessageId==0)historyGeneration++;generation=historyGeneration;
        }
        TdApi.GetChatHistory req=new TdApi.GetChatHistory();
        req.chatId=chatId;
        req.fromMessageId=fromMessageId;
        req.offset=0;
        req.limit=30;
        req.onlyLocal=false;
        active.send(req,obj->{
            ArrayList<MessagePreview> rows=new ArrayList<>();
            String error=null;
            if(obj instanceof TdApi.Messages) {
                TdApi.Message[] messages=((TdApi.Messages)obj).messages;
                if(messages!=null)for(TdApi.Message m:messages)rows.add(preview(m));
            } else {
                error="تاریخچهٔ این چت در دسترس نیست یا مجوز مشاهده ندارید.";
            }
            String finalError=error;
            main.post(()->{
                synchronized(TdlibEngine.this) {
                    if(browser!=requester||client!=active||!loggedIn||generation!=historyGeneration)return;
                }
                requester.onChatHistory(chatId,rows,finalError);
            });
        });
    }

    private MessagePreview preview(TdApi.Message message) {
        String value="پیام غیرمتنی";
        if(message.content instanceof TdApi.MessageText) {
            TdApi.FormattedText formatted=((TdApi.MessageText)message.content).text;
            value=formatted!=null?formatted.text:"";
        } else if(message.content instanceof TdApi.MessagePhoto) {
            TdApi.FormattedText caption=((TdApi.MessagePhoto)message.content).caption;
            value=caption!=null && !caption.text.isEmpty()?caption.text:"عکس";
        }
        if(value==null)value="";
        if(value.length()>700)value=value.substring(0,700)+"…";
        ChatOverview chat;
        synchronized(this) {chat=allKnownChats.get(message.chatId);}
        String chatTitle=chat==null?"چت "+message.chatId:chat.name;
        return new MessagePreview(message.chatId,message.id,chatTitle,value,message.date);
    }

    synchronized void sendApprovedSingleMessage(long chatId,String message,boolean permissionConfirmed) {
        if(!loggedIn||client==null){result("ارسال",false,"حساب هنوز وارد نشده است.");return;}
        if(!permissionConfirmed || !groups.containsKey(chatId)){
            result("ارسال",false,"فقط گروه انتخاب‌شده از فهرست و با تأیید صریح مجوز قابل تست است.");return;
        }
        if(message==null || message.trim().length()==0 || message.length()>3000){
            result("ارسال",false,"متن باید بین ۱ تا ۳۰۰۰ نویسه باشد.");return;
        }
        if(System.currentTimeMillis()<app.getSharedPreferences("tdlib_controls",Context.MODE_PRIVATE).getLong("retry_until",0)) {
            result("ارسال",false,"محدودیت موقت تلگرام فعال است؛ بعداً دوباره بررسی کنید.");return;
        }
        long elapsed=System.currentTimeMillis()-lastAttempt;
        if(elapsed>=0&&elapsed<480000L){
            result("ارسال",false,"حداقل ۸ دقیقه فاصله میان تلاش‌ها لازم است.");return;
        }
        lastAttempt=System.currentTimeMillis();
        app.getSharedPreferences("tdlib_controls",Context.MODE_PRIVATE).edit().putLong("last_attempt",lastAttempt).apply();
        TdApi.SendMessage send=new TdApi.SendMessage();
        send.chatId=chatId;
        send.inputMessageContent=new TdApi.InputMessageText(new TdApi.FormattedText(message.trim(),null),null,true);
        client.send(send,o->{
            if(o instanceof TdApi.Error){recordRateLimit((TdApi.Error)o);respond("ارسال",o);}
            else result("ارسال",true,"درخواست به TDLib تحویل شد؛ تأیید نهایی جداگانه دریافت می‌شود.");
        });
    }


    /** Worker may initialize TDLib after Android restarts the app process.
     * Authentication steps that require the user intentionally pause the queue.
     * No worker attempts to enter a phone, code or password on the user's behalf.
     */
    synchronized boolean awaitReadyForQueue(long timeoutMillis) throws InterruptedException {
        long end=android.os.SystemClock.elapsedRealtime()+timeoutMillis;
        while(!loggedIn || client==null) {
            if("NEED_API".equals(state) || "NATIVE_ERROR".equals(state)
                    || "CONFIG_ERROR".equals(state) || "PHONE".equals(state)
                    || "CODE".equals(state) || "PASSWORD".equals(state)
                    || "EMAIL".equals(state) || "EMAIL_CODE".equals(state)
                    || "REGISTER".equals(state) || "OTHER_DEVICE".equals(state))return false;
            long remaining=end-android.os.SystemClock.elapsedRealtime();
            if(remaining<=0)return false;
            wait(remaining);
        }
        return true;
    }

    /** Reserves one attempt atomically against manual TDLib sends.
     * The 8-minute interval is a MINIMUM regardless of when WorkManager wakes.
     */
    synchronized String reserveScheduledAttempt() {
        if(!loggedIn || client==null)return "حساب TDLib آماده نیست.";
        long remaining=app.getSharedPreferences("tdlib_controls",Context.MODE_PRIVATE).getLong("retry_until",0)-System.currentTimeMillis();
        if(remaining>0)return "WAIT:"+remaining;
        long elapsed=System.currentTimeMillis()-lastAttempt;
        if(elapsed>=0 && elapsed<480000L) return "WAIT:"+(480000L-elapsed);
        lastAttempt=System.currentTimeMillis();
        boolean stored=app.getSharedPreferences("tdlib_controls",Context.MODE_PRIVATE)
                .edit().putLong("last_attempt",lastAttempt).commit();
        return stored ? null : "خطا در ذخیرهٔ محدودیت ارسال.";
    }

    interface QueueCallback {
        /** Accepted means TDLib returned an outgoing Message, not confirmed delivery. */
        void completed(boolean accepted,long pendingMessageId,String detail);
    }

    /** Send one previously approved queue item. No retries, broadcasts or scraping.
     * Check server-side chat type/permissions again immediately before sending.
     */
    void sendQueuedOnce(long chatId,String text,QueueCallback callback) {
        sendQueuedOnce(chatId,text,callback,()->true);
    }
    void sendQueuedOnce(long chatId,String text,QueueCallback callback,java.util.function.BooleanSupplier activeAttempt) {
        final Session active;
        synchronized(this) {
            if(!loggedIn || client==null){
                callback.completed(false,0,"نشست تلگرام بسته شده است.");return;
            }
            active=client;
        }
        active.send(new TdApi.GetChat(chatId),object->{
            if(!activeAttempt.getAsBoolean()||!ScheduledQueue.enabled(app)){
                callback.completed(false,0,"صف متوقف شده است؛ پیام ارسال نشد.");return;
            }
            if(!(object instanceof TdApi.Chat)){
                callback.completed(false,0,"گروه در حساب تلگرام قابل‌دسترسی نیست.");return;
            }
            TdApi.Chat chat=(TdApi.Chat)object;
            boolean group=chat.type instanceof TdApi.ChatTypeBasicGroup
                    || (chat.type instanceof TdApi.ChatTypeSupergroup
                    && !((TdApi.ChatTypeSupergroup)chat.type).isChannel);
            if(!group){
                callback.completed(false,0,"مقصد دیگر یک گروه مجاز نیست.");return;
            }
            TdApi.Function<?> membership=chat.type instanceof TdApi.ChatTypeBasicGroup
                ? new TdApi.GetBasicGroup(((TdApi.ChatTypeBasicGroup)chat.type).basicGroupId)
                : new TdApi.GetSupergroup(((TdApi.ChatTypeSupergroup)chat.type).supergroupId);
            active.send(membership,memberReply->{
            TdApi.ChatMemberStatus member=memberReply instanceof TdApi.BasicGroup?((TdApi.BasicGroup)memberReply).status
                : memberReply instanceof TdApi.Supergroup?((TdApi.Supergroup)memberReply).status:null;
            boolean joined=member instanceof TdApi.ChatMemberStatusMember
                || member instanceof TdApi.ChatMemberStatusAdministrator
                || member instanceof TdApi.ChatMemberStatusCreator&&((TdApi.ChatMemberStatusCreator)member).isMember
                || member instanceof TdApi.ChatMemberStatusRestricted&&((TdApi.ChatMemberStatusRestricted)member).isMember
                    && ((TdApi.ChatMemberStatusRestricted)member).permissions!=null
                    && ((TdApi.ChatMemberStatusRestricted)member).permissions.canSendBasicMessages;
            if(!joined){callback.completed(false,0,"عضویت و مجوز ارسال در گروه تأیید نشد؛ پیام ارسال نشد.");return;}
            // The chat's default permissions apply to ordinary members; an
            // administrator or creator may send basic text even when members cannot.
            boolean privileged=member instanceof TdApi.ChatMemberStatusAdministrator
                || member instanceof TdApi.ChatMemberStatusCreator;
            boolean memberCanSend=chat.permissions!=null && chat.permissions.canSendBasicMessages;
            if(!privileged && !memberCanSend) {
                callback.completed(false,0,"حق ارسال پیام متنی برای این حساب در گروه تأیید نشد.");return;
            }
            synchronized(TdlibEngine.this) {
                if(client!=active || !loggedIn || !activeAttempt.getAsBoolean() || !ScheduledQueue.enabled(app)){
                    callback.completed(false,0,"نشست یا صف تغییر کرد؛ ارسال متوقف شد.");return;
                }
            }
            TdApi.SendMessage outgoing=new TdApi.SendMessage();
            outgoing.chatId=chatId;
            outgoing.inputMessageContent=new TdApi.InputMessageText(
                    new TdApi.FormattedText(text,null),null,true);
            active.send(outgoing,reply->{
                if(reply instanceof TdApi.Message){
                    callback.completed(true,((TdApi.Message)reply).id,
                            "درخواست به TDLib تحویل شد؛ نتیجهٔ نهایی جداگانه بررسی شود.");
                } else if(reply instanceof TdApi.Error) {
                    recordRateLimit((TdApi.Error)reply);
                    callback.completed(false,0,((TdApi.Error)reply).code==429?"تلگرام ارسال را محدود کرد؛ تا پایان محدودیت تلاش تازه انجام نمی‌شود.":"تلگرام ارسال را نپذیرفت؛ مجوز گروه و اتصال را بررسی کنید.");
                } else {
                    callback.completed(false,0,"TDLib ارسال را تأیید نکرد.");
                }
            });
            });
        });
    }

    private void recordRateLimit(TdApi.Error error) {
        if(error.code!=429 && (error.message==null||!error.message.contains("FLOOD_WAIT")))return;
        long seconds=3600;
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?i)(?:FLOOD_WAIT_|retry after\\s+)([0-9]+)").matcher(error.message==null?"":error.message);
        if(match.find())try{seconds=Math.min(31536000L,Math.max(1,Long.parseLong(match.group(1))));}catch(NumberFormatException ignored){}
        app.getSharedPreferences("tdlib_controls",Context.MODE_PRIVATE).edit().putLong("retry_until",System.currentTimeMillis()+seconds*1000).commit();
    }

    synchronized void logout() {
        DiscoverySchedule.disable(app);
        // An explicit Telegram logout revokes unattended access, too.
        TelegramBackgroundSchedule.setEnabled(app,false);
        DiscoveryCoordinator.get(app).stop();
        if(client==null){result("خروج",false,"نشست فعالی وجود ندارد.");return;}
        client.send(new TdApi.LogOut(),o->respond("خروج",o));
    }

    /** API hash is stored encrypted at rest in the Android Keystore.
        TDLib's private database is isolated in internal app storage with backup disabled. */
    private static final class Vault {
        private static final String KEY="electric_connect_tdlib_api_v1";
        private final Context context;
        Vault(Context context){this.context=context;}
        private SharedPreferences prefs(){return context.getSharedPreferences("tdlib_encrypted_api",Context.MODE_PRIVATE);}
        private SecretKey getKey() throws Exception {
            KeyStore store=KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            SecretKey existing=(SecretKey)store.getKey(KEY,null);
            if(existing!=null)return existing;
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            return generator.generateKey();
        }
        boolean has(String field){return prefs().contains(field+"_data");}
        void put(String field,String value)throws Exception{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,getKey());
            byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            prefs().edit().putString(field+"_data",Base64.encodeToString(encrypted,Base64.NO_WRAP))
                .putString(field+"_nonce",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).apply();
        }
        String get(String field)throws Exception{
            String c=prefs().getString(field+"_data",null);
            String n=prefs().getString(field+"_nonce",null);
            if(c==null||n==null)return null;
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,getKey(),new GCMParameterSpec(128,Base64.decode(n,Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(c,Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }
    }
}
