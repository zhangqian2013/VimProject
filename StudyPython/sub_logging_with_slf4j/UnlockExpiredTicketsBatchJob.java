package com.stubhub.job.sell;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StopWatch;

import com.mallardsoft.tuple.Pair;
import com.mallardsoft.tuple.Tuple;
import com.stubhub.common.business.enums.ListingStatus;
import com.stubhub.common.business.manager.SystemSettingMgr;
import com.stubhub.common.config.SpringContextLoader;
import com.stubhub.common.framework.job.AbstractStubHubJob;
import com.stubhub.common.framework.job.Schedule;
import com.stubhub.common.framework.job.StubHubJobExecutionContext;
import com.stubhub.common.framework.job.StubHubJobExecutionException;
import com.stubhub.common.framework.job.TriggerState;
import com.stubhub.common.util.StringUtils;
import com.stubhub.integration.listing.business.util.ExternalListingHelper;
import com.stubhub.integration.tictec.business.entity.enums.FeedbackMessageEnum;
import com.stubhub.inventory.business.entity.Listing;
import com.stubhub.inventory.business.manager.ListingMgrRefactored;
import com.stubhub.mq.entities.ListingMsg;
import com.stubhub.mq.producer.ActivemqProducer;
import com.stubhub.primary.business.entity.PTVSystem;
import com.stubhub.primary.business.entity.enums.PrimaryTicketVendorEnum;
import com.stubhub.primary.business.manager.IntegrationMgr;
import com.stubhub.user.business.manager.SellJobMgr;

/**
 * UnlockExpiredTicketsBatchJob UnlockExpiredTicketsBatchJob gets all the expired primary listings and sends listing ids to unlock
 * listing queue for unlock operation
 * 
 * @author <a href="mailto:sauranjan@stubhub.com">sauranjan</a><BR>
 * 
 */
@Schedule(cronExpression = "0 0/5 * * * ?", 
		jobDescription = "Gets all the expired primary listings and sends listing ids to unlock listing queue for unlock operation", 
		triggerDescription = "Triggers at every 5 minutes",
		state = TriggerState.NORMAL)
public class UnlockExpiredTicketsBatchJob extends AbstractStubHubJob {

    private static final String CONSTANT_ZERO = "0";
    private static final char CONSTANT_COMMA = ',';
    private static final String PROCESS_EXPIRED_PRIMARY_LISTING_JOB_SUCCESSFULLY_COMPLETED = "ProcessExpiredPrimaryListing Job Completed";
    private static final String PROCESS_EXPIRED_PRIMARY_LISTING_JOB_FAILED = "ProcessExpiredPrimaryListing Job Failed";

    @Autowired
    private SystemSettingMgr systemSettingMgr;

    @Autowired
    private SellJobMgr sellJobMgr;

    @Autowired
    private IntegrationMgr integrationMgr;

    @Autowired
    private ListingMgrRefactored listingMgrRefactored;

    /*
     * (non-Javadoc)
     * 
     * @see
     * com.stubhub.common.framework.job.AbstractStubHubJob#executeJob(com.stubhub.common.framework.job.StubHubJobExecutionContext)
     */
    @Override
    protected void executeJob(StubHubJobExecutionContext context) throws StubHubJobExecutionException {
        try {
            getLog().info("UnlockExpiredTicketsBatchJob starts");

            String value = systemSettingMgr.getSystemSettingByName("SellJobFacadeImpl.expireListingsThreaded.flag");
            if (value != null && "true".equals(StringUtils.lowerCase(value))) {
                getLog().info("processExpiredPrimaryListing: Threaded expire listings flag is set using it.");
                this.processExpiredPrimaryListingThreaded();
                return;
            }
            Set<Long> expPrimaryListings = new TreeSet<Long>(sellJobMgr.getExpiredPrimaryListing());
            getLog().info("There are " + expPrimaryListings.size() + " listings to process " + expPrimaryListings);

            this.unlockPrimaryListing(expPrimaryListings);

            String processedListingsCount = (null != expPrimaryListings) ? String.valueOf(expPrimaryListings.size())
                                                                        : CONSTANT_ZERO;
            getLog().info(" ticketIds={}" + getContent(expPrimaryListings) + " processedListingsCount={}" + processedListingsCount);

            // update job progress status
            updateJobProgress(context, "Done with UnlockExpiredTicketsBatchJob");

            // periodically check if we've been aborted...
            if (isAborted()) {
                getLog().warn(String.format("%s aborted bailing out!", context.getJobExecutionContext().getJobDetail().getKey()
                                                                              .getName()));
                return;
            }
            getLog().info("UnlockExpiredTicketsBatchJob stops");
        } catch (Exception e) {
            throw new StubHubJobExecutionException(e.getMessage(), e);
        }
    }

    private void processExpiredPrimaryListingThreaded() throws Exception {
        getLog().info("processExpiredPrimaryListingThreaded entered");
        int maxPollTime = 300000; // 5 mins
        int maxRetries = 10;
        int threadPoolSize = 10;

        String pollTimeMilliSeconds = systemSettingMgr.getSystemSettingByName("SellJobFacadeImpl.expireListingsThreaded.maxPollTime");
        String numberOfRetries = systemSettingMgr.getSystemSettingByName("SellJobFacadeImpl.expireListingsThreaded.numberOfRetries");
        String numberOfThreads = systemSettingMgr.getSystemSettingByName("SellJobFacadeImpl.expireListingsThreaded.threadPoolSize");

        if (pollTimeMilliSeconds != null && StringUtils.isNumeric(pollTimeMilliSeconds)) {
            maxPollTime = Integer.parseInt(pollTimeMilliSeconds);
        }
        if (numberOfRetries != null && StringUtils.isNumeric(numberOfRetries)) {
            maxRetries = Integer.parseInt(numberOfRetries);
        }
        if (numberOfThreads != null && StringUtils.isNumeric(numberOfThreads)) {
            threadPoolSize = Integer.parseInt(numberOfThreads);
        }

        getLog().info("processExpiredPrimaryListingThreaded: using values pollTime=" + maxPollTime + " maxRetries=" + maxRetries
                              + " threadPoolSize=" + threadPoolSize);

        ExecutorService executorService = Executors.newFixedThreadPool(threadPoolSize);
        try {
            CompletionService<Pair<ExpireListingCallable, List<Long>>> expireCompletionService = new ExecutorCompletionService<Pair<ExpireListingCallable, List<Long>>>(
                                                                                                                                                                        executorService);
            List<ExpireListingCallable> submittedJobs = new ArrayList<ExpireListingCallable>();

            ExpireListingCallable job = null;
            String vendorName = null;
            // go through and get list of teams per vendor
            for (PrimaryTicketVendorEnum p : EnumSet.allOf(PrimaryTicketVendorEnum.class)) {
                List<PTVSystem> ptvSystems = integrationMgr.getPTVSystemByVendor(p); // teams by vendor
                if (ptvSystems == null) {
                    continue;
                }
                for (PTVSystem ptvSystem : ptvSystems) {
                    if (!ptvSystem.isIntegrated()) {
                        getLog().info("processExpiredPrimaryListingThreaded: ptvSystem=" + ptvSystem.getName()
                                              + " is not integrated, continuing");
                        continue;
                    }
                    vendorName = (ptvSystem.getPrimaryTicketVendor() != null) ? ptvSystem.getPrimaryTicketVendor().getName() : "";
                    getLog().info("processExpiredPrimaryListingThreaded: Creating job for vendor=" + vendorName + " team="
                                          + ptvSystem.getName());
                    job = new ExpireListingCallable(ptvSystem.getPrimaryTicketVendor().getId(), ptvSystem.getId());
                    submittedJobs.add(job);
                    expireCompletionService.submit(job);
                }
            }

            int numRetries = maxRetries;
            Future<Pair<ExpireListingCallable, List<Long>>> completedTask = null;
            int jobsCompleted = 0;
            while (!submittedJobs.isEmpty()) {
                if (numRetries > 0) {
                    getLog().info("processExpiredPrimaryListingThreaded: polling for a completed job");
                    completedTask = expireCompletionService.poll(maxPollTime, TimeUnit.MILLISECONDS);
                    if (completedTask == null) {
                        getLog().info("processExpiredPrimaryListingThreaded: no job found in ms=" + maxPollTime);
                        if (--numRetries == 0) { // decrement number of retries
                            String msg = "processExpiredPrimaryListingThreaded: Maximum number of retries=" + maxRetries
                                         + " exprired waiting for job completion";
                            getLog().error(msg);
                            throw new Exception(msg);
                        }
                        continue; // continue to poll
                    }
                    numRetries = maxRetries; // reset number of retries since we have found a completed task
                    Pair<ExpireListingCallable, List<Long>> expiredListingPair = completedTask.get();
                    ExpireListingCallable expireListingCallable = Tuple.get1(expiredListingPair);
                    List<Long> expiredListingIds = Tuple.get2(expiredListingPair);
                    submittedJobs.remove(expireListingCallable);
                    StopWatch stopWatch = new StopWatch();
                    stopWatch.start();
                    getLog().info("processExpiredPrimaryListingThreaded: number of listings to send for unlock="
                                          + expiredListingIds.size());
                    this.unlockPrimaryListing(new TreeSet<Long>(expiredListingIds)); // submit the listings
                    stopWatch.stop();
                    getLog().info("processExpiredPrimaryListingThreaded: send complete in (ms)=" + stopWatch.getTotalTimeMillis());
                    jobsCompleted++;
                }
            }

            getLog().info("processExpiredPrimaryListingThreaded: method complete jobsCompleted=" + jobsCompleted);
        } finally {
            executorService.shutdownNow();
        }
    }

    public void unlockPrimaryListing(Set<Long> listingIdSet) throws Exception {
        getLog().info("unlockPrimaryListing method entered");
        try {
            ActivemqProducer producer = null;
            boolean skipUnlockCallForUser = false;
            producer = (ActivemqProducer) SpringContextLoader.getInstance().getBean("unlockPrimaryListingProducer");

            for (Long listingId : listingIdSet) {
                try {
                    Listing listing = listingMgrRefactored.getFullListing(listingId, null);
                    // Making sure listing is in Deleted or Expired status.
                    if (listing != null && listing.getDeliveryOption().isPreDelivery() && listing.getTicketMedium().isBarcode()) {
                        Calendar currentDate = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                        if (ListingStatus.DELETED.toString().equals(listing.getStatus())
                            || listing.getSellOptions().getEndDate().before(currentDate)) {
                            skipUnlockCallForUser = ExternalListingHelper.isBarcodeLockingIgnoredForSeller(listing.getSellerId());
                            // to skip unlock call for trusted sellers
                            if (!skipUnlockCallForUser) {
                                producer.sentMessage(getListingMessage(listingId));
                                getLog().info("Unlocking listing id " + listingId);
                                try {
                                    ExternalListingHelper.sendFeedback(listing,
                                                                       FeedbackMessageEnum.BARCODE_LISTING_UNLOCKED_ON_PRIMARY);
                                } catch (Exception e) {
                                    getLog().error("Exception while sending feedback message during unlock of primary listings"
                                                           + listing.getId(), e);
                                }
                            }
                        } else {
                            getLog().info("Unlock not allowed for listing id " + listingId + ". Listing is not deleted or expired");
                        }
                    }
                } catch (Exception e) {
                    getLog().error("Unable to send message for listing with Id: " + listingId, e);
                }
            }
        } catch (Exception e) {
            throw e;
        }
    }

    class ExpireListingCallable implements Callable<Pair<ExpireListingCallable, List<Long>>> {
        private long vendorId;
        private long teamId;

        public ExpireListingCallable(long vendorId, long teamId) {
            this.vendorId = vendorId;
            this.teamId = teamId;
        }

        @Override
        public Pair<ExpireListingCallable, List<Long>> call() throws Exception {
            StopWatch stopWatch = new StopWatch("ExpireListingCallable");
            stopWatch.start();
            getLog().info("ExpireListingCallable started for vendorid=" + this.vendorId + " teamId=" + this.teamId);
            List<Long> expiredListings = sellJobMgr.getExpiredPrimaryListingsByVendorAndTeam(this.vendorId, this.teamId);
            stopWatch.stop();
            getLog().info("ExpireListingCallable(ms)=" + stopWatch.getTotalTimeMillis() + " vendorid=" + this.vendorId + " teamId="
                                  + this.teamId);
            if (expiredListings == null)
                expiredListings = new ArrayList<Long>(); // method returns empty list atm but check just in case it changes in the
                                                         // future
            return new Pair<ExpireListingCallable, List<Long>>(this, expiredListings);
        }
    }

    public static String getContent(Set<Long> ticketIdSet) {
        int ticketCtr = 1;
        if (null != ticketIdSet && !ticketIdSet.isEmpty()) {
            StringBuffer sb = new StringBuffer();
            for (Long ticketId : ticketIdSet) {
                sb.append(ticketId);
                if (ticketCtr < ticketIdSet.size()) {
                    sb.append(CONSTANT_COMMA);
                    ticketCtr++;
                }
            }
            return sb.toString();
        }
        return null;
    }

    private ListingMsg getListingMessage(Long listingId) {

        ListingMsg msg = new ListingMsg();
        msg.setListingId(listingId);
        return msg;
    }

}