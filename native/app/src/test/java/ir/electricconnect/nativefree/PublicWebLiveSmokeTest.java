package ir.electricconnect.nativefree;

import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** Explicit real-source integration check, excluded from deterministic offline runs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class PublicWebLiveSmokeTest {
    @Test public void crawlPublicBuyerSiteSaveDeduplicateAndExportRealContacts()throws Exception {
        Assume.assumeTrue("Run separately with EC_LIVE_PUBLIC_SMOKE=1", "1".equals(System.getenv("EC_LIVE_PUBLIC_SMOKE")));
        Context app=RuntimeEnvironment.getApplication();
        app.deleteDatabase("electric_connect_native_free.db");
        try(MainActivity.Store store=new MainActivity.Store(app)) {
            DiscoveryData data=new DiscoveryData(store);
            DiscoveryCoordinator.Run run=new DiscoveryCoordinator.Run(data,new AtomicBoolean(),null);
            run.seeds.add("https://nicsaco.com/industrial-power-system-design/");run.maxHosts=1;run.maxPages=3;
            // URLConnection uses the managed environment's configured HTTPS proxy/CA.
            // The production provider's parsing, robots policy, bounds and persistence run unchanged.
            new PublicWebsiteContactsProvider((url,hosts)->{
                String safe=PublicHttp.validate(url,hosts);
                HttpURLConnection connection=(HttpURLConnection)new URL(safe).openConnection();
                connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent","ElectricConnect/0.10 (+public business contacts; no login)");
                try {
                    int code=connection.getResponseCode();ByteArrayOutputStream body=new ByteArrayOutputStream();
                    InputStream source=code>=400?connection.getErrorStream():connection.getInputStream();
                    if(source!=null)try(InputStream in=source){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(body.size()+n>1048576)throw new IOException();body.write(b,0,n);}}
                    return new PublicHttp.Page(code,body.toString("UTF-8"),Objects.toString(connection.getContentType(),""),Objects.toString(connection.getHeaderField("Location"),""));
                }finally {connection.disconnect();}
            }).discover(run);
            System.out.println("REAL_PUBLIC_SMOKE pages="+run.pages+" saved="+run.saved+" duplicate_rows="+run.duplicates+" errors="+run.errors);
            assertTrue("No real public page retrieved",run.pages>0);
            assertTrue("No labeled public buyer phones found",run.saved>0);
            List<String[]> rows=new ArrayList<>();rows.add(new String[]{"نام کسب‌وکار","شماره تماس","منبع","نوع منبع"});
            for(MainActivity.Record record:store.find("",0,100)){
                assertTrue(record.source.startsWith("https://nicsaco.com/"));
                assertFalse(store.insertPublicBusinessPhone(record.title,record.phone,record.source));
                rows.add(new String[]{record.title,record.phone,record.source,record.provider});
            }
            String output=System.getenv("EC_LIVE_PUBLIC_OUTPUT");
            if(output!=null)try(OutputStream out=new FileOutputStream(output)){ExcelWorkbook.write(out,"مخاطبان عمومی واقعی",rows);}
        }
    }
}
