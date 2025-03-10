package com.ultish.jikangaaruserver.timeCharges

import com.ultish.jikangaaruserver.entities.ETrackedTask
import com.ultish.jikangaaruserver.listeners.getIdFrom
import com.ultish.jikangaaruserver.trackedTasks.TrackedTaskService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener
import org.springframework.data.mongodb.core.mapping.event.AfterDeleteEvent
import org.springframework.data.mongodb.core.mapping.event.BeforeDeleteEvent
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveEvent
import org.springframework.stereotype.Component


@Component
class TimeChargeTrackedTaskListener : AbstractMongoEventListener<ETrackedTask>() {
    @Autowired
    lateinit var timeChargeService: TimeChargeService

    @Autowired
    lateinit var trackedTaskService: TrackedTaskService

    @Autowired
    lateinit var timeCalcService: TimeCalcService

    private final val logger = LoggerFactory.getLogger(this::class.java)

    override fun onBeforeSave(event: BeforeSaveEvent<ETrackedTask>) {

        logger.info("onBeforeSave ${event.source.id}")
        val existingTrackedTask = trackedTaskService.repository.findById(event.source.id)
        val existingTimeSlots = if (existingTrackedTask.isPresent) {
            existingTrackedTask.get().timeSlots
        } else {
            listOf()
        }

        val chargeCodesChanged = if (existingTrackedTask.isPresent) {
            val existingCCs = existingTrackedTask.get().chargeCodeIds
            val newCCs = event.source.chargeCodeIds - existingCCs.toSet();
            val delCCs = existingCCs - event.source.chargeCodeIds.toSet();

            (newCCs + delCCs).isNotEmpty()
        } else {
            false
        }

        val timeSlotsChanged = if (chargeCodesChanged) {
            logger.debug("Charge codes changed, re-calculating all timeslot charges")
            event.source.timeSlots
        } else {
            val addedTimeSlots = event.source.timeSlots.minus(existingTimeSlots.toSet())
            val removedTimeSlots = existingTimeSlots.minus(event.source.timeSlots.toSet())
            logger.debug("added timeslots: $addedTimeSlots")
            logger.debug("removed timeslots: $removedTimeSlots")

            addedTimeSlots + removedTimeSlots
        }

        val trackedTaskToSave = event.source
        val userId = trackedTaskToSave.userId

        timeCalcService.updateTimeCharges(
            userId,
            trackedTaskToSave,
            trackedTaskToSave.trackedDayId,
            timeSlotsChanged
        )
    }

    override fun onAfterDelete(event: AfterDeleteEvent<ETrackedTask>) {
        super.onAfterDelete(event)

        // TODO as these mongo events are limiting it may be time to move to custom events instead.
        //  That way you can always provide the difference of before and after save or delete
        //  For now, we're jamming data into the IDs of the objects which is dodgy
        val id = getIdFrom(event)
        // the id for a tracked task is: ["$userId:$trackedDayId:${ObjectId()}"] where $trackedDayId is made of userId:uuid
        val (userId, _, trackedDayId, _) = id?.split(":") ?: listOf()

        if (trackedDayId != null) {
            timeChargeService.resetTimeCharges(userId, "$userId:$trackedDayId")
        }
    }

    override fun onBeforeDelete(event: BeforeDeleteEvent<ETrackedTask>) {
        super.onBeforeDelete(event)

    }

}