package org.opengeo.gwcdistributed.seed;

import static org.junit.Assert.*;
import static org.easymock.classextension.EasyMock.*;
import static org.hamcrest.Matchers.*;

import java.util.Collection;
import java.util.Collections;

import org.geowebcache.layer.TileLayer;
import org.geowebcache.layer.TileLayerDispatcher;
import org.geowebcache.seed.AbstractJobTest;
import org.geowebcache.seed.GWCTask;
import org.geowebcache.seed.GWCTask.STATE;
import org.geowebcache.seed.Job;
import org.geowebcache.seed.JobStatus;
import org.geowebcache.seed.SeedTask;
import org.geowebcache.seed.TaskStatus;
import org.geowebcache.storage.TileRange;
import org.geowebcache.storage.TileRangeIterator;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.hazelcast.core.Hazelcast;

public class DistributedTileBreederIntegrationTest {
	static final String CONTEXT_PATH = "classpath:/org/opengeo/gwcdistributed/seed/distributedTileBreederTest-context.xml";
	static final String BREEDER_BEAN_NAME = "breeder";
	
	ApplicationContext ctxt1;
	ApplicationContext ctxt2;
	DistributedTileBreederMockedTasks breeder1;
	DistributedTileBreederMockedTasks breeder2;
	
	@Before
	public void setUp(){
		
		ctxt1=new ClassPathXmlApplicationContext(CONTEXT_PATH);
		ctxt2=new ClassPathXmlApplicationContext(CONTEXT_PATH);
		
		breeder1 = (DistributedTileBreederMockedTasks) ctxt1.getBean(BREEDER_BEAN_NAME);
		breeder2 = (DistributedTileBreederMockedTasks) ctxt2.getBean(BREEDER_BEAN_NAME);
	}

	@After
	public void tearDown(){
		System.out.println("Shutting down Hazelcast.");
		Hazelcast.shutdownAll();
	}
	
	
	@Test
	public void testDistributeJob() throws Exception {
		
		Collection<SeedTask> tasks1 = Collections.singleton(createMock(SeedTask.class));
		for(SeedTask task: tasks1){
			expect(task.getState()).andStubReturn(STATE.READY);
			//replay(task);
		}
		breeder1.seedIt=tasks1.iterator();
		
		Collection<SeedTask> tasks2 = Collections.singleton(createMock(SeedTask.class));
		for(SeedTask task: tasks2){
			expect(task.getState()).andStubReturn(STATE.READY);
			//replay(task);
		}
		breeder2.seedIt=tasks2.iterator();
		
		@SuppressWarnings("deprecation")
		TileRange tr = new TileRange("testLayer", null, 0, 0, null, null, (String) null);
		
		TileRangeIterator trItr = createMock(TileRangeIterator.class); {
			expect(trItr.getTileRange()).andStubReturn(tr);
		} replay(trItr);
		
		TileLayer tl = createMock(TileLayer.class); {
			expect(tl.getName()).andStubReturn("testLayer");
			expect(tl.getMetaTilingFactors()).andStubReturn(new int[]{1,1});
		} replay(tl);
		
		TileLayerDispatcher tld = createMock(TileLayerDispatcher.class); {
			expect(tld.getTileLayer("testLayer")).andStubReturn(tl);
		} replay(tld);
		
		breeder1.setTileLayerDispatcher(tld);
		breeder2.setTileLayerDispatcher(tld);
		
		Job job = breeder1.createJob(tr, GWCTask.TYPE.SEED, 1, false);
		final long id = job.getId();

		System.out.println("Waiting to give the job a chance to propagate");
		Thread.sleep(2000);
		
		for(SeedTask task: tasks1){
			verify(task);
		}
		for(SeedTask task: tasks2){
			verify(task);
		}

		assertThat(((DistributedJob)job).getTasks(), Matchers.arrayContainingInAnyOrder(tasks1.toArray()));
		assertThat(((DistributedJob)breeder1.getJobByID(id)).getTasks(), Matchers.arrayContainingInAnyOrder(tasks1.toArray()));
		assertThat(((DistributedJob)breeder2.getJobByID(id)).getTasks(), Matchers.arrayContainingInAnyOrder(tasks2.toArray()));
		
	}
	
	@Test
	public void testDispatchJob() throws Exception {
		
		Collection<SeedTask> tasks1 = Collections.singleton(createMock(SeedTask.class));
		long taskId=0;
		for(SeedTask task: tasks1){
			expect(task.getState()).andStubReturn(STATE.READY);
			expect(task.getTaskId()).andStubReturn(taskId++);
			AbstractJobTest.expectDoActionInternal(task);
			AbstractJobTest.expectDispose(task);
			//replay(task);
		}
		breeder1.seedIt=tasks1.iterator();
		
		Collection<SeedTask> tasks2 = Collections.singleton(createMock(SeedTask.class));
		for(SeedTask task: tasks2){
			expect(task.getState()).andStubReturn(STATE.READY);
			expect(task.getTaskId()).andStubReturn(taskId++);
			AbstractJobTest.expectDoActionInternal(task);
			AbstractJobTest.expectDispose(task);
			//replay(task);
		}
		breeder2.seedIt=tasks2.iterator();
		
		@SuppressWarnings("deprecation")
		TileRange tr = new TileRange("testLayer", null, 0, 0, null, null, (String) null);
		
		TileRangeIterator trItr = createMock(TileRangeIterator.class); {
			expect(trItr.getTileRange()).andStubReturn(tr);
		} replay(trItr);
		
		TileLayer tl = createMock(TileLayer.class); {
			expect(tl.getName()).andStubReturn("testLayer");
			expect(tl.getMetaTilingFactors()).andStubReturn(new int[]{1,1});
		} replay(tl);
		
		TileLayerDispatcher tld = createMock(TileLayerDispatcher.class); {
			expect(tld.getTileLayer("testLayer")).andStubReturn(tl);
		} replay(tld);
		
		breeder1.setTileLayerDispatcher(tld);
		breeder2.setTileLayerDispatcher(tld);
		
		Job job = breeder1.createJob(tr, GWCTask.TYPE.SEED, 1, false);
		final long id = job.getId();
		
		System.out.println("Waiting to give the job a chance to propagate");
		Thread.sleep(2000);
		
		for(SeedTask task: tasks1){
			verify(task);
		}
		for(SeedTask task: tasks2){
			verify(task);
		}
	}
	
	@Test
	public void testTerminateJob() throws Exception {
		
		Collection<SeedTask> tasks1 = Collections.singleton(createMock(SeedTask.class));
		long taskId=0;
		for(SeedTask task: tasks1){
			expect(task.getState()).andStubReturn(STATE.READY);
			expect(task.getTaskId()).andStubReturn(taskId++);
			AbstractJobTest.expectDoActionInternal(task);
			AbstractJobTest.expectDispose(task);
			//replay(task);
		}
		breeder1.seedIt=tasks1.iterator();
		
		Collection<SeedTask> tasks2 = Collections.singleton(createMock(SeedTask.class));
		for(SeedTask task: tasks2){
			expect(task.getState()).andStubReturn(STATE.READY);
			expect(task.getTaskId()).andStubReturn(taskId++);
			AbstractJobTest.expectDoActionInternal(task);
			AbstractJobTest.expectDispose(task);
			//replay(task);
		}
		breeder2.seedIt=tasks2.iterator();
		
		@SuppressWarnings("deprecation")
		TileRange tr = new TileRange("testLayer", null, 0, 0, null, null, (String) null);
		
		TileRangeIterator trItr = createMock(TileRangeIterator.class); {
			expect(trItr.getTileRange()).andStubReturn(tr);
		} replay(trItr);
		
		TileLayer tl = createMock(TileLayer.class); {
			expect(tl.getName()).andStubReturn("testLayer");
			expect(tl.getMetaTilingFactors()).andStubReturn(new int[]{1,1});
		} replay(tl);
		
		TileLayerDispatcher tld = createMock(TileLayerDispatcher.class); {
			expect(tld.getTileLayer("testLayer")).andStubReturn(tl);
		} replay(tld);
		
		breeder1.setTileLayerDispatcher(tld);
		breeder2.setTileLayerDispatcher(tld);
		
		Job job = breeder1.createJob(tr, GWCTask.TYPE.SEED, 1, false);
		final long id = job.getId();
		
		System.out.println("Waiting to give the job a chance to propagate");
		Thread.sleep(2000);
		
		
		// Check that the right calls were made for dispatch, then expect 
		// the calls for termination
		for(SeedTask task: tasks1){
			verify(task);
			
			reset(task);
			task.terminateNicely();
			expect(task.getState()).andStubReturn(STATE.RUNNING);
			replay(task);
		}
		for(SeedTask task: tasks2){
			verify(task);
			
			reset(task);
			task.terminateNicely();
			expect(task.getState()).andStubReturn(STATE.RUNNING);
			replay(task);
		}
		// Terminate
		job.terminate();
		
		// Verify termination
		for(SeedTask task: tasks1){
			verify(task);
		}
		for(SeedTask task: tasks2){
			verify(task);
		}
		
	}

	@Test
	public void testStatus() throws Exception {
		
		Collection<SeedTask> tasks1 = Collections.singleton(createMock(SeedTask.class));
		long taskId=0;
		for(SeedTask task: tasks1){
			expect(task.getState()).andStubReturn(STATE.READY);
			expect(task.getTaskId()).andStubReturn(taskId++);
			AbstractJobTest.expectDoActionInternal(task);
			AbstractJobTest.expectDispose(task);
			//replay(task);
		}
		breeder1.seedIt=tasks1.iterator();
		
		Collection<SeedTask> tasks2 = Collections.singleton(createMock(SeedTask.class));
		for(SeedTask task: tasks2){
			expect(task.getState()).andStubReturn(STATE.READY);
			expect(task.getTaskId()).andStubReturn(taskId++);
			AbstractJobTest.expectDoActionInternal(task);
			AbstractJobTest.expectDispose(task);
			//replay(task);
		}
		breeder2.seedIt=tasks2.iterator();
		
		@SuppressWarnings("deprecation")
		TileRange tr = new TileRange("testLayer", null, 0, 0, null, null, (String) null);
		
		TileRangeIterator trItr = createMock(TileRangeIterator.class); {
			expect(trItr.getTileRange()).andStubReturn(tr);
		} replay(trItr);
		
		TileLayer tl = createMock(TileLayer.class); {
			expect(tl.getName()).andStubReturn("testLayer");
			expect(tl.getMetaTilingFactors()).andStubReturn(new int[]{1,1});
		} replay(tl);
		
		TileLayerDispatcher tld = createMock(TileLayerDispatcher.class); {
			expect(tld.getTileLayer("testLayer")).andStubReturn(tl);
		} replay(tld);
		
		breeder1.setTileLayerDispatcher(tld);
		breeder2.setTileLayerDispatcher(tld);
		
		Job job = breeder1.createJob(tr, GWCTask.TYPE.SEED, 1, false);
		final long id = job.getId();
		
		System.out.println("Waiting to give the job a chance to propagate");
		Thread.sleep(2000);
		
		
		// Check that the right calls were made for dispatch, then expect 
		// the calls for termination
		int i = 0;
		for(SeedTask task: tasks1){
			verify(task);

			TaskStatus status = createMock(DistributedTaskStatus.class);
			// TODO
			replay(status);
			
			reset(task);
			expect(task.getState()).andStubReturn(STATE.RUNNING);
			expect(task.getStatus()).andReturn(status);
			replay(task);
		}
		for(SeedTask task: tasks2){
			verify(task);
			
			TaskStatus status = createMock(DistributedTaskStatus.class);
			// TODO
			replay(status);

			reset(task);
			expect(task.getState()).andStubReturn(STATE.RUNNING);
			expect(task.getStatus()).andReturn(status);
			replay(task);
		}
		// Terminate
		JobStatus jStatus = job.getStatus();
		
		// Verify termination
		for(SeedTask task: tasks1){
			verify(task);
		}
		for(SeedTask task: tasks2){
			verify(task);
		}
		
		
	}

}
