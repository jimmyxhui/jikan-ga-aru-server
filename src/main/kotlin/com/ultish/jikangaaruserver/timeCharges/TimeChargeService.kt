package com.ultish.jikangaaruserver.timeCharges

import com.netflix.graphql.dgs.*
import com.netflix.graphql.dgs.context.DgsContext
import com.querydsl.core.BooleanBuilder
import com.ultish.generated.DgsConstants
import com.ultish.generated.types.ChargeCode
import com.ultish.generated.types.TimeCharge
import com.ultish.generated.types.TrackedDay
import com.ultish.jikangaaruserver.chargeCodes.ChargeCodeService
import com.ultish.jikangaaruserver.contexts.CustomContext
import com.ultish.jikangaaruserver.dataFetchers.dgsData
import com.ultish.jikangaaruserver.dataFetchers.dgsQuery
import com.ultish.jikangaaruserver.dataFetchers.getEntitiesFromEnv
import com.ultish.jikangaaruserver.entities.ETimeCharge
import com.ultish.jikangaaruserver.entities.QETimeCharge
import com.ultish.jikangaaruserver.trackedDays.TrackedDayService
import graphql.schema.DataFetchingEnvironment
import org.dataloader.MappedBatchLoaderWithContext
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CompletableFuture

@DgsComponent
class TimeChargeService {

    private companion object {
        const val DATA_LOADER_FOR_CHARGE_CODE = "chargeCodeForTimeCharge"
        const val DATA_LOADER_FOR_TRACKED_DAY = "trackedDayForTimeCharge"
    }


    @Autowired
    lateinit var repository: TimeChargeRepository

    @Autowired
    lateinit var timeCalcService: TimeCalcService

    @Autowired
    lateinit var chargeCodeService: ChargeCodeService

    @Autowired
    lateinit var trackedDayService: TrackedDayService


    @DgsQuery
    fun timeCharges(
        dfe: DataFetchingEnvironment,
        @InputArgument trackedDayId: String? = null,
        @InputArgument timeSlot: Int? = null,
        @InputArgument chargeCodeId: String? = null,
    ): List<TimeCharge> {
        return dgsQuery(dfe) {
            val builder = BooleanBuilder()

            trackedDayId?.let {
                builder.and(QETimeCharge.eTimeCharge.trackedDayId.eq(trackedDayId))
            }
            timeSlot?.let {
                builder.and(QETimeCharge.eTimeCharge.timeSlot.eq(timeSlot))
            }
            chargeCodeId?.let {
                builder.and(QETimeCharge.eTimeCharge.chargeCodeId.eq(chargeCodeId))
            }
            repository.findAll(builder)
        }
    }


    fun resetTimeCharges(userId: String, trackedDayId: String) {
        // full time range 0=00:00, 1=00:06, 2=00:12, etc to X=23:54, 10 per hour
        val timeSlots = (0..240).toList()

        timeCalcService.updateTimeCharges(
            trackedDayId = trackedDayId,
            timeSlotsChanged = timeSlots,
            userId = userId,
        )
    }

    //
    // Document References (relationships)
    // -------------------------------------------------------------------------
    @DgsData(
        parentType = DgsConstants.TIMECHARGE.TYPE_NAME,
        field = DgsConstants.TIMECHARGE.ChargeCode
    )
    fun relatedChargeCode(dfe: DataFetchingEnvironment): CompletableFuture<ChargeCode> {
        return dgsData<ChargeCode, TimeCharge>(dfe, DATA_LOADER_FOR_CHARGE_CODE) {
            it.id
        }
    }

    @DgsData(
        parentType = DgsConstants.TIMECHARGE.TYPE_NAME,
        field = DgsConstants.TIMECHARGE.TrackedDay
    )
    fun relatedTrackedDay(dfe: DataFetchingEnvironment): CompletableFuture<TrackedDay> {
        return dgsData<TrackedDay, TimeCharge>(dfe, DATA_LOADER_FOR_TRACKED_DAY) {
            it.id
        }
    }

    //
    // Data Loaders
    // -------------------------------------------------------------------------
    @DgsDataLoader(name = DATA_LOADER_FOR_CHARGE_CODE, caching = true)
    val loadForTrackedTaskBatchLoader = MappedBatchLoaderWithContext<String, ChargeCode> { timeChargeIds, environment ->
        CompletableFuture.supplyAsync {
            // Relationship: Many-To-One

            val customContext = DgsContext.getCustomContext<CustomContext>(environment)

            val timeChargeToChargeCodeMap = getEntitiesFromEnv<String, ETimeCharge>(environment, timeChargeIds) {
                it.chargeCodeId
            }

            val chargeCodeMap = chargeCodeService.repository.findAllById(
                timeChargeToChargeCodeMap.values.toList()
            )
                .associateBy { it.id }

            // TODO not sure how these contexts are used in a federated graphQL scenario. I assume it probably wouldn't
            //  and I'd have to re-implement the logic to fetch from DB for the related trackedDayIds here if this was
            //  split into it's own microservice
            // pass down to next level if needed
            customContext.entities.addAll(chargeCodeMap.values)

            timeChargeToChargeCodeMap.keys.associateWith { timeChargeId ->
                val chargeCode =
                    timeChargeToChargeCodeMap[timeChargeId]?.let { chargeCodeMap[it] }
                chargeCode?.toGqlType()
            }
        }
    }

    @DgsDataLoader(name = DATA_LOADER_FOR_TRACKED_DAY, caching = true)
    val loadForTrackedDayBatchLoader = MappedBatchLoaderWithContext<String, TrackedDay> { timeChargeIds, environment ->
        CompletableFuture.supplyAsync {
            // Relationship: Many-To-One

            val customContext = DgsContext.getCustomContext<CustomContext>(environment)

            val timeChargeToTrackedDayMap = getEntitiesFromEnv<String, ETimeCharge>(environment, timeChargeIds) { it ->
                it.trackedDayId
            }

            val trackedDayMap = trackedDayService.repository.findAllById(
                timeChargeToTrackedDayMap.values.toList()
            )
                .associateBy { it.id }

            // TODO not sure how these contexts are used in a federated graphQL scenario. I assume it probably wouldn't
            //  and I'd have to re-implement the logic to fetch from DB for the related trackedDayIds here if this was
            //  split into it's own microservice
            // pass down to next level if needed
            customContext.entities.addAll(trackedDayMap.values)

            timeChargeToTrackedDayMap.keys.associateWith { timeChargeId ->
                val trackedDay =
                    timeChargeToTrackedDayMap[timeChargeId]?.let { trackedDayMap[it] }

                trackedDay?.toGqlType()
            }
        }
    }
}
