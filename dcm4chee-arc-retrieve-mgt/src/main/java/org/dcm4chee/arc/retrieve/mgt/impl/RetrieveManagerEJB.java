/*
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 *  The contents of this file are subject to the Mozilla Public License Version
 *  1.1 (the "License"); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *  http://www.mozilla.org/MPL/
 *
 *  Software distributed under the License is distributed on an "AS IS" basis,
 *  WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 *  for the specific language governing rights and limitations under the
 *  License.
 *
 *  The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 *  Java(TM), hosted at https://github.com/dcm4che.
 *
 *  The Initial Developer of the Original Code is
 *  J4Care.
 *  Portions created by the Initial Developer are Copyright (C) 2015-2017
 *  the Initial Developer. All Rights Reserved.
 *
 *  Contributor(s):
 *  See @authors listed below
 *
 *  Alternatively, the contents of this file may be used under the terms of
 *  either the GNU General Public License Version 2 or later (the "GPL"), or
 *  the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 *  in which case the provisions of the GPL or the LGPL are applicable instead
 *  of those above. If you wish to allow use of your version of this file only
 *  under the terms of either the GPL or the LGPL, and not to allow others to
 *  use your version of this file under the terms of the MPL, indicate your
 *  decision by deleting the provisions above and replace them with the notice
 *  and other provisions required by the GPL or the LGPL. If you do not delete
 *  the provisions above, a recipient may use your version of this file under
 *  the terms of any one of the MPL, the GPL or the LGPL.
 *
 */

package org.dcm4chee.arc.retrieve.mgt.impl;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.jpa.hibernate.HibernateQuery;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.Device;
import org.dcm4chee.arc.conf.ArchiveDeviceExtension;
import org.dcm4chee.arc.entity.*;
import org.dcm4chee.arc.event.QueueMessageEvent;
import org.dcm4chee.arc.qmgt.HttpServletRequestInfo;
import org.dcm4chee.arc.qmgt.IllegalTaskStateException;
import org.dcm4chee.arc.qmgt.QueueManager;
import org.dcm4chee.arc.qmgt.QueueSizeLimitExceededException;
import org.dcm4chee.arc.retrieve.ExternalRetrieveContext;
import org.dcm4chee.arc.retrieve.mgt.RetrieveBatch;
import org.dcm4chee.arc.retrieve.mgt.RetrieveManager;
import org.dcm4chee.arc.retrieve.mgt.RetrieveTaskQuery;
import org.hibernate.Session;
import org.hibernate.StatelessSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.Stateless;
import javax.ejb.TransactionAttribute;
import javax.ejb.TransactionAttributeType;
import javax.inject.Inject;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.ObjectMessage;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @author Vrinda Nayak <vrinda.nayak@j4care.com>
 * @since Oct 2017
 */
@Stateless
public class RetrieveManagerEJB {
    private static final Logger LOG = LoggerFactory.getLogger(RetrieveManagerEJB.class);

    @PersistenceContext(unitName = "dcm4chee-arc")
    private EntityManager em;

    @Inject
    private QueueManager queueManager;

    @Inject
    private Device device;

    private static final Expression<?>[] SELECT = {
            QQueueMessage.queueMessage.processingStartTime.min(),
            QQueueMessage.queueMessage.processingStartTime.max(),
            QQueueMessage.queueMessage.processingEndTime.min(),
            QQueueMessage.queueMessage.processingEndTime.max(),
            QQueueMessage.queueMessage.scheduledTime.min(),
            QQueueMessage.queueMessage.scheduledTime.max(),
            QRetrieveTask.retrieveTask.createdTime.min(),
            QRetrieveTask.retrieveTask.createdTime.max(),
            QRetrieveTask.retrieveTask.updatedTime.min(),
            QRetrieveTask.retrieveTask.updatedTime.max(),
            QQueueMessage.queueMessage.batchID
    };

    public boolean scheduleRetrieveTask(Device device, int priority, ExternalRetrieveContext ctx, String batchID,
                                        Date notRetrievedAfter, long delay)
            throws QueueSizeLimitExceededException {
        if (isAlreadyScheduledOrRetrievedAfter(em, ctx, notRetrievedAfter)) {
            return false;
        }
        try {
            ObjectMessage msg = queueManager.createObjectMessage(ctx.getKeys());
            msg.setStringProperty("LocalAET", ctx.getLocalAET());
            msg.setStringProperty("RemoteAET", ctx.getRemoteAET());
            msg.setIntProperty("Priority", priority);
            msg.setStringProperty("DestinationAET", ctx.getDestinationAET());
            msg.setStringProperty("StudyInstanceUID", ctx.getStudyInstanceUID());
            HttpServletRequestInfo.copyTo(ctx.getHttpServletRequestInfo(), msg);
            QueueMessage queueMessage = queueManager.scheduleMessage(RetrieveManager.QUEUE_NAME, msg,
                    Message.DEFAULT_PRIORITY, batchID, delay);
            createRetrieveTask(device, ctx, queueMessage);
            return true;
        } catch (JMSException e) {
            throw QueueMessage.toJMSRuntimeException(e);
        }
    }

    private boolean isAlreadyScheduledOrRetrievedAfter(
            EntityManager em, ExternalRetrieveContext ctx, Date retrievedAfter) {
        Predicate statusPredicate = QRetrieveTask.retrieveTask.queueMessage.status.in(
                QueueMessage.Status.SCHEDULED, QueueMessage.Status.IN_PROCESS);
        if (retrievedAfter != null) {
            statusPredicate = ExpressionUtils.or(statusPredicate,
                    QRetrieveTask.retrieveTask.updatedTime.after(retrievedAfter));
        }
        BooleanBuilder predicate = new BooleanBuilder(statusPredicate);
        predicate.and(QRetrieveTask.retrieveTask.remoteAET.eq(ctx.getRemoteAET()));
        predicate.and(QRetrieveTask.retrieveTask.destinationAET.eq(ctx.getDestinationAET()));
        predicate.and(QRetrieveTask.retrieveTask.studyInstanceUID.eq(ctx.getStudyInstanceUID()));
        if (ctx.getSeriesInstanceUID() == null) {
            predicate.and(QRetrieveTask.retrieveTask.seriesInstanceUID.isNull());
        } else {
            predicate.and(ExpressionUtils.or(
                    QRetrieveTask.retrieveTask.seriesInstanceUID.isNull(),
                    QRetrieveTask.retrieveTask.seriesInstanceUID.eq(ctx.getSeriesInstanceUID())));
            if (ctx.getSOPInstanceUID() == null) {
                predicate.and(QRetrieveTask.retrieveTask.sopInstanceUID.isNull());
            } else {
                predicate.and(ExpressionUtils.or(
                        QRetrieveTask.retrieveTask.sopInstanceUID.isNull(),
                        QRetrieveTask.retrieveTask.sopInstanceUID.eq(ctx.getSOPInstanceUID())));
            }
        }
        RetrieveTask prevTask = new HibernateQuery<RetrieveTask>(em.unwrap(Session.class))
                .from(QRetrieveTask.retrieveTask)
                .where(predicate)
                .fetchFirst();
        if (prevTask != null) {
            LOG.info("Previous {} found - suppress duplicate retrieve", prevTask);
            return true;
        }
        return false;
    }

    private void createRetrieveTask(Device device, ExternalRetrieveContext ctx, QueueMessage queueMessage) {
        RetrieveTask task = new RetrieveTask();
        task.setLocalAET(ctx.getLocalAET());
        task.setRemoteAET(ctx.getRemoteAET());
        task.setDestinationAET(ctx.getDestinationAET());
        task.setStudyInstanceUID(ctx.getStudyInstanceUID());
        task.setSeriesInstanceUID(ctx.getSeriesInstanceUID());
        task.setSOPInstanceUID(ctx.getSOPInstanceUID());
        task.setQueueMessage(queueMessage);
        em.persist(task);
    }

    public void updateRetrieveTask(QueueMessage queueMessage, Attributes cmd) {
        em.createNamedQuery(RetrieveTask.UPDATE_BY_QUEUE_MESSAGE)
                .setParameter(1, queueMessage)
                .setParameter(2, cmd.getInt(Tag.NumberOfRemainingSuboperations, 0))
                .setParameter(3, cmd.getInt(Tag.NumberOfCompletedSuboperations, 0))
                .setParameter(4, cmd.getInt(Tag.NumberOfFailedSuboperations, 0))
                .setParameter(5, cmd.getInt(Tag.NumberOfWarningSuboperations, 0))
                .setParameter(6, cmd.getInt(Tag.Status, 0))
                .setParameter(7, cmd.getString(Tag.ErrorComment, null))
                .executeUpdate();
    }

    public void resetRetrieveTask(QueueMessage queueMessage) {
        em.createNamedQuery(RetrieveTask.UPDATE_BY_QUEUE_MESSAGE)
                .setParameter(1, queueMessage)
                .setParameter(2, -1)
                .setParameter(3, 0)
                .setParameter(4, 0)
                .setParameter(5, 0)
                .setParameter(6, -1)
                .setParameter(7, null)
                .executeUpdate();
    }

    public long countRetrieveTasks(Predicate matchQueueMessage, Predicate matchRetrieveTask) {
        return createQuery(matchQueueMessage, matchRetrieveTask)
                .fetchCount();
    }

    private HibernateQuery<RetrieveTask> createQuery(
            Predicate matchQueueMessage, Predicate matchRetrieveTask) {
        HibernateQuery<QueueMessage> queueMsgQuery = new HibernateQuery<QueueMessage>(em.unwrap(Session.class))
                .from(QQueueMessage.queueMessage)
                .where(matchQueueMessage);
        return new HibernateQuery<RetrieveTask>(em.unwrap(Session.class))
                .from(QRetrieveTask.retrieveTask)
                .where(matchRetrieveTask, QRetrieveTask.retrieveTask.queueMessage.in(queueMsgQuery));
    }

    public boolean deleteRetrieveTask(Long pk, QueueMessageEvent queueEvent) {
        RetrieveTask task = em.find(RetrieveTask.class, pk);
        if (task == null)
            return false;

        queueManager.deleteTask(task.getQueueMessage().getMessageID(), queueEvent);
        LOG.info("Delete {}", task);
        return true;
    }

    public boolean cancelRetrieveTask(Long pk, QueueMessageEvent queueEvent) throws IllegalTaskStateException {
        RetrieveTask task = em.find(RetrieveTask.class, pk);
        if (task == null)
            return false;

        QueueMessage queueMessage = task.getQueueMessage();
        if (queueMessage == null)
            throw new IllegalTaskStateException("Cannot cancel Task with status: 'TO SCHEDULE'");

        queueManager.cancelTask(queueMessage.getMessageID(), queueEvent);
        LOG.info("Cancel {}", task);
        return true;
    }
    
    public long cancelRetrieveTasks(Predicate matchQueueMessage, Predicate matchRetrieveTask, QueueMessage.Status prev)
            throws IllegalTaskStateException {
        return queueManager.cancelRetrieveTasks(matchQueueMessage, matchRetrieveTask, prev);
    }

    public String findDeviceNameByPk(Long pk) {
        try {
            return em.createNamedQuery(RetrieveTask.FIND_DEVICE_BY_PK, String.class)
                    .setParameter(1, pk)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    public void rescheduleRetrieveTask(Long pk, QueueMessageEvent queueEvent) {
        RetrieveTask task = em.find(RetrieveTask.class, pk);
        if (task == null)
            return;

        LOG.info("Reschedule {}", task);
        rescheduleRetrieveTask(task.getQueueMessage().getMessageID(), queueEvent);
    }

    public void rescheduleRetrieveTask(String retrieveTaskQueueMsgId, QueueMessageEvent queueEvent) {
        queueManager.rescheduleTask(retrieveTaskQueueMsgId, RetrieveManager.QUEUE_NAME, queueEvent);
    }

    public List<String> listRetrieveTaskQueueMsgIDs(Predicate matchQueueMsg, Predicate matchRetrieveTask, int limit) {
        return createQuery(matchQueueMsg, matchRetrieveTask)
                .select(QQueueMessage.queueMessage.messageID)
                .limit(limit)
                .fetch();
    }

    public int deleteTasks(Predicate matchQueueMessage, Predicate matchRetrieveTask, int deleteTasksFetchSize) {
        List<String> referencedQueueMsgIDs = createQuery(matchQueueMessage, matchRetrieveTask)
                    .select(QRetrieveTask.retrieveTask.queueMessage.messageID)
                    .limit(deleteTasksFetchSize)
                    .fetch();

        for (String queueMsgID : referencedQueueMsgIDs)
            queueManager.deleteTask(queueMsgID, null);

        return referencedQueueMsgIDs.size();
    }

    public List<String> listDistinctDeviceNames(Predicate matchQueueMessage, Predicate matchRetrieveTask) {
        return createQuery(matchQueueMessage, matchRetrieveTask)
                .select(QQueueMessage.queueMessage.deviceName)
                .distinct()
                .fetch();
    }

    public List<RetrieveBatch> listRetrieveBatches(Predicate matchQueueBatch, Predicate matchRetrieveBatch,
                                                   OrderSpecifier<Date> order, int offset, int limit) {
        HibernateQuery<RetrieveTask> retrieveTaskQuery = createQuery(matchQueueBatch, matchRetrieveBatch);
        if (limit > 0)
            retrieveTaskQuery.limit(limit);
        if (offset > 0)
            retrieveTaskQuery.offset(offset);

        List<Tuple> batches = retrieveTaskQuery.select(SELECT)
                                .groupBy(QQueueMessage.queueMessage.batchID)
                                .orderBy(order)
                                .fetch();
        
        List<RetrieveBatch> retrieveBatches = new ArrayList<>();
        for (Tuple batch : batches) {
            RetrieveBatch retrieveBatch = new RetrieveBatch();
            String batchID = batch.get(QQueueMessage.queueMessage.batchID);
            retrieveBatch.setBatchID(batchID);

            retrieveBatch.setCreatedTimeRange(
                    batch.get(QRetrieveTask.retrieveTask.createdTime.min()),
                    batch.get(QRetrieveTask.retrieveTask.createdTime.max()));
            retrieveBatch.setUpdatedTimeRange(
                    batch.get(QRetrieveTask.retrieveTask.updatedTime.min()),
                    batch.get(QRetrieveTask.retrieveTask.updatedTime.max()));
            retrieveBatch.setScheduledTimeRange(
                    batch.get(QQueueMessage.queueMessage.scheduledTime.min()),
                    batch.get(QQueueMessage.queueMessage.scheduledTime.max()));
            retrieveBatch.setProcessingStartTimeRange(
                    batch.get(QQueueMessage.queueMessage.processingStartTime.min()),
                    batch.get(QQueueMessage.queueMessage.processingStartTime.max()));
            retrieveBatch.setProcessingEndTimeRange(
                    batch.get(QQueueMessage.queueMessage.processingEndTime.min()),
                    batch.get(QQueueMessage.queueMessage.processingEndTime.max()));

            retrieveBatch.setDeviceNames(
                    batchIDQuery(batchID)
                        .select(QQueueMessage.queueMessage.deviceName)
                        .distinct()
                        .orderBy(QQueueMessage.queueMessage.deviceName.asc())
                        .fetch());
            retrieveBatch.setLocalAETs(
                    batchIDQuery(batchID)
                        .select(QRetrieveTask.retrieveTask.localAET)
                        .distinct()
                        .orderBy(QRetrieveTask.retrieveTask.localAET.asc())
                        .fetch());
            retrieveBatch.setRemoteAETs(
                    batchIDQuery(batchID)
                        .select(QRetrieveTask.retrieveTask.remoteAET)
                        .distinct()
                        .orderBy(QRetrieveTask.retrieveTask.remoteAET.asc())
                        .fetch());
            retrieveBatch.setDestinationAETs(
                    batchIDQuery(batchID)
                        .select(QRetrieveTask.retrieveTask.destinationAET)
                        .distinct()
                        .orderBy(QRetrieveTask.retrieveTask.destinationAET.asc())
                        .fetch());

            retrieveBatch.setCompleted(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.COMPLETED))
                        .fetchCount());
            retrieveBatch.setCanceled(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.CANCELED))
                        .fetchCount());
            retrieveBatch.setWarning(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.WARNING))
                        .fetchCount());
            retrieveBatch.setFailed(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.FAILED))
                        .fetchCount());
            retrieveBatch.setScheduled(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.SCHEDULED))
                        .fetchCount());
            retrieveBatch.setInProcess(
                    batchIDQuery(batchID)
                        .where(QQueueMessage.queueMessage.status.eq(QueueMessage.Status.IN_PROCESS))
                        .fetchCount());

            retrieveBatches.add(retrieveBatch);
        }

        return retrieveBatches;
    }

    private HibernateQuery<RetrieveTask> batchIDQuery(String batchID) {
        return new HibernateQuery<RetrieveTask>(em.unwrap(Session.class))
                .from(QRetrieveTask.retrieveTask)
                .leftJoin(QRetrieveTask.retrieveTask.queueMessage, QQueueMessage.queueMessage)
                .where(QQueueMessage.queueMessage.batchID.eq(batchID));
    }

    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public RetrieveTaskQuery listRetrieveTasks(Predicate matchQueueMessage, Predicate matchRetrieveTask,
                                               OrderSpecifier<Date> order, int offset, int limit) {
        return new RetrieveTaskQueryImpl(
                openStatelessSession(), queryFetchSize(), matchQueueMessage, matchRetrieveTask, order, offset, limit);
    }

    private StatelessSession openStatelessSession() {
        return em.unwrap(Session.class).getSessionFactory().openStatelessSession();
    }

    private int queryFetchSize() {
        return device.getDeviceExtensionNotNull(ArchiveDeviceExtension.class).getQueryFetchSize();
    }
}
