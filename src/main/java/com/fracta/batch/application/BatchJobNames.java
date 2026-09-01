package com.fracta.batch.application;

/** Job 이름은 메타테이블·스케줄러·운영 화면이 공유하므로 상수로 고정한다. */
public final class BatchJobNames {

    public static final String DAILY_RECONCILIATION = "DailyReconciliationJob";
    public static final String CHAIN_VERIFICATION = "ChainVerificationJob";
    public static final String SETTLEMENT_REPORT = "SettlementReportJob";
    public static final String BROKER_TOKEN_REFRESH = "BrokerTokenRefreshJob";
    public static final String SUBSCRIPTION_ALLOTMENT = "SubscriptionAllotmentJob";
    public static final String API_LOG_ARCHIVE = "ApiLogArchiveJob";

    private BatchJobNames() {
    }
}
