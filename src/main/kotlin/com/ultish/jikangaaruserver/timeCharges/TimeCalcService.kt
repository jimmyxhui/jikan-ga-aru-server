package com.ultish.jikangaaruserver.timeCharges

import com.querydsl.core.BooleanBuilder
import com.ultish.jikangaaruserver.entities.ETimeCharge
import com.ultish.jikangaaruserver.entities.ETrackedTask
import com.ultish.jikangaaruserver.entities.QETimeCharge
import com.ultish.jikangaaruserver.entities.QETrackedTask
import com.ultish.jikangaaruserver.trackedTasks.TrackedTaskService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * this function is async for better performance, using virtual threads no need to set a pool
 */
@Service
@EnableAsync
class TimeCalcService {

    private final val logger = LoggerFactory.getLogger(this::class.java)
    private val lockMap = ConcurrentHashMap<String, ReentrantLock>()

    @Autowired
    lateinit var trackedTaskService: TrackedTaskService

    @Autowired
    lateinit var repository: TimeChargeRepository


    @Async
    fun updateTimeCharges(
        userId: String,
        trackedTaskToSave: ETrackedTask? = null,
        trackedDayId: String,
        timeSlotsChanged: List<Int>,
    ) {
        logger.info("updateTimeCharges ${trackedDayId}")
        // Lock only the affected timeSlots
        val locks = timeSlotsChanged.sorted().map { lockMap.computeIfAbsent("$trackedDayId:$it") { ReentrantLock() } }
        locks.forEach { it.lock() }


        try {
            // Find all the Tracked Tasks that use any of the TimeSlots that have changed, these will need new TimeCharge
            // calculations
            val affectedTrackedTasks = trackedTaskService.repository.findAll(
                BooleanBuilder()
                    .and(
                        QETrackedTask.eTrackedTask.timeSlots.any()
                            .`in`(timeSlotsChanged)
                    )
                    .and(QETrackedTask.eTrackedTask.trackedDayId.eq(trackedDayId))
//                .and(QETrackedTask.eTrackedTask.userId.eq(userId))
            )
                .toMutableList()

            if (trackedTaskToSave != null) {
                // this function is called during beforeSave, so remove the stored version
                affectedTrackedTasks.removeIf { it.id == trackedTaskToSave.id }
                // and add the before saved version
                affectedTrackedTasks.add(trackedTaskToSave)
                // TODO may be odd, since the saved version hasn't made it to the DB and could fail
            }

            // For each TimeSlot group any TrackedTasks that use it
            val timeSlotToTrackedTasksMap = timeSlotsChanged.associateBy({ it }, { timeSlot ->
                affectedTrackedTasks.filter {
                    it.timeSlots.contains(timeSlot)
                }
            })

            val timeSlotToTimeChargesMap = repository.findAll(
                BooleanBuilder()
                    .and(QETimeCharge.eTimeCharge.timeSlot.`in`(timeSlotsChanged))
                    .and(QETimeCharge.eTimeCharge.trackedDayId.eq(trackedDayId))
            )
                .groupBy { it.timeSlot }

            val toDelete = mutableSetOf<ETimeCharge>()

            val allTimeCharges: List<ETimeCharge> = timeSlotToTrackedTasksMap.entries.flatMap { entry ->
                val timeSlot = entry.key
                val trackedTasksAtTimeSlot = entry.value

                // find out how many charge codes are used, including duplicates
                val allChargeCodes = trackedTasksAtTimeSlot.flatMap { trackedTask -> trackedTask.chargeCodeIds }
                val numberOfChargeCodes = allChargeCodes.size
                val chargeCodeIdsAtTimeSlot = allChargeCodes.distinct()

                // find TimeCharges for ChargeCodes at this TimeSlot that aren't used anymore
                val timeChargesForTimeSlot = timeSlotToTimeChargesMap[timeSlot]

                val timeChargesNotUsedByChargeCodesAnymore = timeChargesForTimeSlot?.filter {
                    !chargeCodeIdsAtTimeSlot.contains(it.chargeCodeId)
                } ?: listOf()
                toDelete.addAll(timeChargesNotUsedByChargeCodesAnymore)

                logger.debug("These TimeCharges aren't used by ChargeCodes at timeslot $timeSlot: $timeChargesNotUsedByChargeCodesAnymore")

                // map to TimeCharge per chargecode ID
                val timeCharges = chargeCodeIdsAtTimeSlot.map { chargeCodeId ->
                    val chargeCodeAppearance = trackedTasksAtTimeSlot.count { trackedTask ->
                        trackedTask.chargeCodeIds.contains(chargeCodeId)
                    }

                    ETimeCharge(
                        timeSlot = timeSlot,
                        chargeCodeAppearance = chargeCodeAppearance,
                        totalChargeCodesForSlot = numberOfChargeCodes,
                        trackedDayId = trackedDayId,
                        chargeCodeId = chargeCodeId,
                        userId = userId,
                    )
                }

                logger.debug(timeCharges.toString())
                // TODO find existing timeCharge by trackedDayId and timeSlot

                return@flatMap timeCharges
            }

            val ids = allTimeCharges.map { it.id }

            // find existing timeCharges
            val existingTimeCharges = repository.findAllById(ids)
            val newTimeCharges = allTimeCharges.minus(existingTimeCharges.toSet())


            repository.deleteAll(toDelete)

            existingTimeCharges.forEach {
                updateTimeCharge(it, it.chargeCodeAppearance, it.totalChargeCodesForSlot)
            }

            newTimeCharges.forEach {
                repository.save(it)
            }
        } finally {
            locks.forEach { it.unlock() }
        }
    }

    fun updateTimeCharge(
        timeCharge: ETimeCharge,
        chargeCodeAppearance: Int? = null,
        totalChargeCodesForSlot: Int? = null,
    ): ETimeCharge {
        return timeCharge.copy(
            chargeCodeAppearance = chargeCodeAppearance ?: timeCharge.chargeCodeAppearance,
            totalChargeCodesForSlot = totalChargeCodesForSlot ?: timeCharge.totalChargeCodesForSlot
        )
    }
}