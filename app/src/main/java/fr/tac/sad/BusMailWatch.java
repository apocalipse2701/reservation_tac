package fr.tac.sad;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Relève régulière de la boîte mail tant qu'une demande de bus attend sa réponse. */
public class BusMailWatch extends JobService {

    private static final int JOB_ID = 91;

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, BusMailWatch.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(30 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        } catch (Throwable t) {
            CrashLog.save(c, t);
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context c = getApplicationContext();
        CrashLog.install(c);
        if (BusMail.pending(c).length() == 0) return false;
        new Thread(() -> {
            BusMail.checkAndTell(c);
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
