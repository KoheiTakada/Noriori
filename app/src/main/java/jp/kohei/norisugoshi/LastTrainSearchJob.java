package jp.kohei.norisugoshi;

import android.app.job.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** Network lookups use a background job; they do not require a visible location service. */
public final class LastTrainSearchJob extends JobService {
    static final int ID=4520;
    private static final ExecutorService EXECUTOR=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"last-train-search");t.setDaemon(true);return t;});
    private static final TransitLastTrainClient CLIENT=new TransitLastTrainClient();
    private Request running;
    private static final class Request { final JobParameters params;volatile boolean stopped;Request(JobParameters p){params=p;} }
    static boolean eligible(Context c){
        return LastTrainEngine.activeTime(LastTrainEngine.now())&&LastTrainStore.home(c)!=null&&"notify".equals(LastTrainStore.prefs(c).getString("zone",""))
                &&!LastTrainStore.muted(c,LastTrainEngine.now());
    }
    static void enqueue(Context c){
        enqueue(c,false);
    }
    private static void enqueue(Context c,boolean replace){
        if(!eligible(c))return;
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);if(scheduler==null)return;
        if(!replace&&scheduler.getPendingJob(ID)!=null)return;
        JobInfo job=new JobInfo.Builder(ID,new ComponentName(c,LastTrainSearchJob.class))
                .setMinimumLatency(1000).setPersisted(true)
                .setBackoffCriteria(10*60_000L,JobInfo.BACKOFF_POLICY_LINEAR).build();
        if(scheduler.schedule(job)==JobScheduler.RESULT_FAILURE)LastTrainStore.problem(c,"終電を確認できない");
    }
    static void cancel(Context c){JobScheduler scheduler=c.getSystemService(JobScheduler.class);if(scheduler!=null)scheduler.cancel(ID);}
    @Override public boolean onStartJob(JobParameters params){
        if(!eligible(this))return false;
        Request request=new Request(params);running=request;
        EXECUTOR.execute(()->search(request));return true;
    }
    private void search(Request request){
        Context c=getApplicationContext();boolean retry=false;double lat=Double.NaN,lon=Double.NaN;long generation=-1;
        try{
            if(request.stopped||!eligible(c))return;
            long now=LastTrainEngine.now(),fixAt=LastTrainStore.prefs(c).getLong("fixAt",0);
            if(fixAt<=0||now-fixAt>120_000||fixAt>now+10_000){LastTrainCoordinator.requestFix(c);retry=true;return;}
            lat=Double.parseDouble(LastTrainStore.prefs(c).getString("fixLat","NaN"));
            lon=Double.parseDouble(LastTrainStore.prefs(c).getString("fixLon","NaN"));
            if(!Double.isFinite(lat)||Math.abs(lat)>85||!Double.isFinite(lon)||Math.abs(lon)>180)return;
            HomeStation home=LastTrainStore.home(c);generation=LastTrainStore.generation(c);
            try{
                LastTrainRoute result=CLIENT.search(c,lat,lon,home,now);
                if(!request.stopped)LastTrainCoordinator.completeSearch(c,generation,result,null);
            }catch(Exception e){
                retry=true;if(!request.stopped)LastTrainCoordinator.completeSearch(c,generation,null,e);
            }
        }finally{
            boolean reschedule=retry&&eligible(c);double oldLat=lat,oldLon=lon;long oldGeneration=generation;
            new Handler(Looper.getMainLooper()).post(()->{
                if(request.stopped||running!=request)return;running=null;
                jobFinished(request.params,reschedule);
                if(!reschedule&&eligible(c)&&Double.isFinite(oldLat)&&Double.isFinite(oldLon)){
                    try{
                        double currentLat=Double.parseDouble(LastTrainStore.prefs(c).getString("fixLat","NaN"));
                        double currentLon=Double.parseDouble(LastTrainStore.prefs(c).getString("fixLon","NaN"));
                        if(LastTrainStore.generation(c)!=oldGeneration||Geo.distanceMeters(oldLat,oldLon,currentLat,currentLon)>=200)enqueue(c,true);
                    }catch(NumberFormatException ignored){}
                }
            });
        }
    }
    @Override public boolean onStopJob(JobParameters params){if(running!=null)running.stopped=true;return eligible(this);}
}
