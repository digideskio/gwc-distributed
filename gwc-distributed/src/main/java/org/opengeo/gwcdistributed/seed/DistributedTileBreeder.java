package org.opengeo.gwcdistributed.seed;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.layer.TileLayer;
import org.geowebcache.layer.TileLayerDispatcher;
import org.geowebcache.seed.GWCTask;
import org.geowebcache.seed.GWCTask.STATE;
import org.geowebcache.seed.GWCTask.TYPE;
import org.geowebcache.seed.Job;
import org.geowebcache.seed.JobNotFoundException;
import org.geowebcache.seed.JobStatus;
import org.geowebcache.seed.SeedJob;
import org.geowebcache.seed.SeedRequest;
import org.geowebcache.seed.SeedTask;
import org.geowebcache.seed.SeederThreadPoolExecutor;
import org.geowebcache.seed.TaskStatus;
import org.geowebcache.seed.TileBreeder;
import org.geowebcache.seed.TruncateJob;
import org.geowebcache.seed.TruncateTask;
import org.geowebcache.storage.StorageBroker;
import org.geowebcache.storage.TileRange;
import org.geowebcache.storage.TileRangeIterator;
import org.geowebcache.util.GWCVars;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.ISet;
import com.hazelcast.core.IdGenerator;
import com.hazelcast.core.ItemEvent;
import com.hazelcast.core.ItemListener;
import com.hazelcast.core.Member;
import com.hazelcast.core.MultiTask;

public class DistributedTileBreeder extends TileBreeder implements ApplicationContextAware {

	public DistributedTileBreeder(HazelcastInstance hz) {
		super();
		this.hz = hz;
		this.currentTaskId = hz.getIdGenerator("taskIdGenerator");
		this.currentJobId = hz.getIdGenerator("jobIdGenerator");
		this.jobCloud = hz.getSet("jobCloud");
		jobCloud.addItemListener(new JobAddedListener(), true);
	}
	
	private final ISet<DistributedJob> jobCloud;

	private final HazelcastInstance hz;
    private static final String GWC_SEED_ABORT_LIMIT = "GWC_SEED_ABORT_LIMIT";

    private static final String GWC_SEED_RETRY_WAIT = "GWC_SEED_RETRY_WAIT";

    private static final String GWC_SEED_RETRY_COUNT = "GWC_SEED_RETRY_COUNT";

    private static Log log = LogFactory.getLog(DistributedTileBreeder.class);

    /**
     * How many retries per failed tile. 0 = don't retry, 1 = retry once if failed, etc
     */
    private int tileFailureRetryCount = 0;

    /**
     * How much (in milliseconds) to wait before trying again a failed tile
     */
    private long tileFailureRetryWaitTime = 100;

    /**
     * How many failures to tolerate before aborting the seed task. Value is shared between all the
     * threads of the same run.
     */
    private long totalFailuresBeforeAborting = 1000;

    private Map<Long, SubmittedTask> currentPool = new TreeMap<Long, SubmittedTask>();

    private final IdGenerator currentTaskId;
    private final IdGenerator currentJobId;

    //private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private static class SubmittedTask {
        public final GWCTask task;

        public final Future<GWCTask> future;

        public SubmittedTask(final GWCTask task, final Future<GWCTask> future) {
            this.task = task;
            this.future = future;
        }
    }
    
    /**
     * Callback for new jobs being added to the cluster
     */
	private class JobAddedListener implements ItemListener<DistributedJob> {

		
		public void itemAdded(ItemEvent<DistributedJob> item) {
			DistributedJob j = item.getItem();
			log.info(String.format("Job %d added to cluster, adding to breeder on node %s\n",j.getId(), hz.getName()));
			jobs.put(j.getId(), j);
		}

		public void itemRemoved(ItemEvent<DistributedJob> item) {
			// TODO Auto-generated method stub
			
		}
		
	}

    
    /**
     * Initializes the seed task failure control variables either with the provided environment
     * variable values or their defaults.
     * 
     * @see {@link ThreadedTileBreeder class' javadocs} for more information
     * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
     */
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        String retryCount = GWCVars.findEnvVar(applicationContext, GWC_SEED_RETRY_COUNT);
        String retryWait = GWCVars.findEnvVar(applicationContext, GWC_SEED_RETRY_WAIT);
        String abortLimit = GWCVars.findEnvVar(applicationContext, GWC_SEED_ABORT_LIMIT);

        tileFailureRetryCount = (int) toLong(GWC_SEED_RETRY_COUNT, retryCount, 0);
        tileFailureRetryWaitTime = toLong(GWC_SEED_RETRY_WAIT, retryWait, 100);
        totalFailuresBeforeAborting = toLong(GWC_SEED_ABORT_LIMIT, abortLimit, 1000);

        checkPositive(tileFailureRetryCount, GWC_SEED_RETRY_COUNT);
        checkPositive(tileFailureRetryWaitTime, GWC_SEED_RETRY_WAIT);
        checkPositive(totalFailuresBeforeAborting, GWC_SEED_ABORT_LIMIT);
    }
    @SuppressWarnings("serial")
    private void checkPositive(long value, String variable) {
        if (value < 0) {
            throw new BeanInitializationException(
                    "Invalid configuration value for environment variable " + variable
                            + ". It should be a positive integer.") {
            };
        }
    }
    private long toLong(String varName, String paramVal, long defaultVal) {
        if (paramVal == null) {
            return defaultVal;
        }
        try {
            return Long.valueOf(paramVal);
        } catch (NumberFormatException e) {
            log.warn("Invalid environment parameter for " + varName + ": '" + paramVal
                    + "'. Using default value: " + defaultVal);
        }
        return defaultVal;
    }

	@Override
	public void seed(String layerName, SeedRequest sr)
			throws GeoWebCacheException {

        TileLayer tl = findTileLayer(layerName);

        TileRange tr = createTileRange(sr, tl);

        Job job = createJob(tr, tl, sr.getType(), sr.getThreadCount(),
                sr.getFilterUpdate());

        dispatchJob(job);
	}

	@Override
	public void dispatchJob(Job job) {
		log.info(String.format("Dispatching Job %d to cluster.  Originating with node %s;", job.getId(), hz.getName()));
		final Set<Member> members = getHz().getCluster().getMembers();
		final MultiTask<Object> mtask = new MultiTask<Object>(new DoDispatch(job.getId()), members);
		getHz().getExecutorService().submit(mtask);
		try {
			mtask.get();
		} catch (ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	void localDispatchJob(Job job) {
		log.debug(String.format("Dispatching Job %d on node %s", job.getId(), hz.getName()));
		for(GWCTask task: job.getTasks()){
			try {
				log.trace(String.format("Starting task %d for job %d on node %s", task.getTaskId(), job.getId(), hz.getName()));
				task.doAction();
			} catch (Exception e) {
				log.error(String.format("Exception dispatching Job %d Task %d on node %s \n", job.getId(), task.getTaskId(), hz.getName()));
			}
		}
	}

	@Override
	public long[][] getStatusList() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public long[][] getStatusList(String layerName) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<JobStatus> getJobStatusList() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<JobStatus> getJobStatusList(String layerName) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<TaskStatus> getTaskStatusList() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<TaskStatus> getTaskStatusList(String layerName) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setThreadPoolExecutor(SeederThreadPoolExecutor stpe) {
		// TODO Auto-generated method stub

	}

	@Override
	public void setStorageBroker(StorageBroker sb) {
		// TODO Auto-generated method stub

	}

	@Override
	public StorageBroker getStorageBroker() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public TileLayer findTileLayer(String layerName)
			throws GeoWebCacheException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Iterator<GWCTask> getRunningTasks() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Iterator<GWCTask> getRunningAndPendingTasks() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Iterator<GWCTask> getPendingTasks() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean terminateGWCTask(long id) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Iterable<TileLayer> getLayers() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected long getNextTaskId() {
		// TODO Auto-generated method stub
		return 0;
	}

	/**
	 * Get the HazelCast instance for the node this Breeder is running on. 
	 * @return
	 */
	public HazelcastInstance getHz() {
		return hz;
	}

	@Override
	protected Callable<GWCTask> wrapTask(GWCTask task) {
		return super.wrapTask(task);
	}
	@Override
	protected void setTaskState(GWCTask task, STATE state) {
		super.setTaskState(task, state);
	}
	@Override
	protected SeedTask createSeedTask(SeedJob job) {
		return super.createSeedTask(job);
	}
	@Override
	protected TruncateTask createTruncateTask(TruncateJob job) {
		return super.createTruncateTask(job);
	}

	@Override
	public SeedJob createSeedJob(int threadCount, boolean reseed,
			TileRangeIterator trIter, TileLayer tl, boolean filterUpdate) {
		DistributedSeedJob job = new DistributedSeedJob(currentJobId.newId(), this, tl, threadCount, trIter, filterUpdate);
		log.trace(String.format("Seed Job %d created on node %s, adding to cluster.",job.getId(), hz.getName()));
		jobCloud.add(job);
		return job;
	}
	@Override
	public TruncateJob createTruncateJob(TileRangeIterator trIter,
			TileLayer tl, boolean filterUpdate) {
		DistributedTruncateJob job = new DistributedTruncateJob(currentJobId.newId(), this, tl, trIter, filterUpdate);
		log.trace(String.format("Truncate Job %d created on node %s, adding to cluster.",job.getId(), hz.getName()));
		jobCloud.add(job);
		return job;
	}

}
