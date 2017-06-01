/*
 * Copyright (C) 2017 Seoul National University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.snu.vortex.runtime.executor;

import com.google.protobuf.ByteString;
import edu.snu.vortex.client.JobConf;
import edu.snu.vortex.compiler.frontend.Coder;
import edu.snu.vortex.compiler.ir.Element;
import edu.snu.vortex.runtime.common.RuntimeAttribute;
import edu.snu.vortex.runtime.common.RuntimeIdGenerator;
import edu.snu.vortex.runtime.common.comm.ControlMessage;
import edu.snu.vortex.runtime.common.message.MessageContext;
import edu.snu.vortex.runtime.common.message.MessageEnvironment;
import edu.snu.vortex.runtime.common.message.MessageListener;
import edu.snu.vortex.runtime.common.plan.physical.ScheduledTaskGroup;
import edu.snu.vortex.runtime.exception.IllegalMessageException;
import edu.snu.vortex.runtime.exception.UnsupportedBlockStoreException;
import edu.snu.vortex.runtime.executor.block.BlockManagerWorker;
import edu.snu.vortex.runtime.executor.datatransfer.DataTransferFactory;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.reef.tang.annotations.Parameter;

import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executor.
 */
public final class Executor {
  private static final Logger LOG = Logger.getLogger(Executor.class.getName());

  private final String executorId;

  /**
   * To be used for a thread pool to execute task groups.
   */
  private final ExecutorService executorService;

  /**
   * In charge of this executor's intermediate data transfer.
   */
  private final BlockManagerWorker blockManagerWorker;

  /**
   * Factory of InputReader/OutputWriter for executing tasks groups.
   */
  private final DataTransferFactory dataTransferFactory;

  private TaskGroupStateManager taskGroupStateManager;

  private final PersistentConnectionToMaster persistentConnectionToMaster;

  @Inject
  public Executor(@Parameter(JobConf.ExecutorId.class) final String executorId,
                  @Parameter(JobConf.ExecutorCapacity.class) final int executorCapacity,
                  final PersistentConnectionToMaster persistentConnectionToMaster,
                  final MessageEnvironment messageEnvironment,
                  final BlockManagerWorker blockManagerWorker,
                  final DataTransferFactory dataTransferFactory) {
    this.executorId = executorId;
    this.executorService = Executors.newFixedThreadPool(executorCapacity);
    this.blockManagerWorker = blockManagerWorker;
    this.dataTransferFactory = dataTransferFactory;
    this.persistentConnectionToMaster = persistentConnectionToMaster;
    messageEnvironment.setupListener(MessageEnvironment.EXECUTOR_MESSAGE_RECEIVER, new ExecutorMessageReceiver());

  }

  public String getExecutorId() {
    return executorId;
  }

  private synchronized void onTaskGroupReceived(final ScheduledTaskGroup scheduledTaskGroup) {
    LOG.log(Level.FINE, "Executor [{0}] received TaskGroup [{1}] to execute.",
        new Object[]{executorId, scheduledTaskGroup.getTaskGroup().getTaskGroupId()});
    executorService.execute(() -> launchTaskGroup(scheduledTaskGroup));
  }

  /**
   * Launches the TaskGroup, and keeps track of the execution state with taskGroupStateManager.
   * @param scheduledTaskGroup to launch.
   */
  private void launchTaskGroup(final ScheduledTaskGroup scheduledTaskGroup) {
    taskGroupStateManager = new TaskGroupStateManager(scheduledTaskGroup.getTaskGroup(),
        executorId, persistentConnectionToMaster);

    scheduledTaskGroup.getTaskGroupIncomingEdges()
        .forEach(e -> blockManagerWorker.registerCoder(e.getId(), e.getCoder()));
    scheduledTaskGroup.getTaskGroupOutgoingEdges()
        .forEach(e -> blockManagerWorker.registerCoder(e.getId(), e.getCoder()));

    new TaskGroupExecutor(scheduledTaskGroup.getTaskGroup(),
        taskGroupStateManager,
        scheduledTaskGroup.getTaskGroupIncomingEdges(),
        scheduledTaskGroup.getTaskGroupOutgoingEdges(),
        dataTransferFactory).execute();
  }

  /**
   * MessageListener for Executor.
   */
  private final class ExecutorMessageReceiver implements MessageListener<ControlMessage.Message> {

    @Override
    public void onMessage(final ControlMessage.Message message) {
      LOG.log(Level.INFO, "onMessage: {0}", message);
      switch (message.getType()) {
      case ScheduleTaskGroup:
        final ControlMessage.ScheduleTaskGroupMsg scheduleTaskGroupMsg = message.getScheduleTaskGroupMsg();
        final ScheduledTaskGroup scheduledTaskGroup =
            SerializationUtils.deserialize(scheduleTaskGroupMsg.getTaskGroup().toByteArray());
        onTaskGroupReceived(scheduledTaskGroup);
        break;
      default:
        throw new IllegalMessageException(
            new Exception("This message should not be received by an executor :" + message.getType()));
      }
    }

    @Override
    public void onMessageWithContext(final ControlMessage.Message message, final MessageContext messageContext) {
      LOG.log(Level.INFO, "onMessageWithContext: {0}", message);
      switch (message.getType()) {
      case RequestBlock:
        final ControlMessage.RequestBlockMsg requestBlockMsg = message.getRequestBlockMsg();

        final Iterable<Element> data = blockManagerWorker.getBlock(requestBlockMsg.getBlockId(),
            requestBlockMsg.getRuntimeEdgeId(), convertBlockStoreType(requestBlockMsg.getBlockStore()));

        final Coder coder = blockManagerWorker.getCoder(requestBlockMsg.getRuntimeEdgeId());
        final ArrayList<byte[]> dataToSerialize = new ArrayList<>();
        for (final Element element : data) {
          try (final ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
            coder.encode(element, stream);
            dataToSerialize.add(stream.toByteArray());
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }

        messageContext.reply(
            ControlMessage.Message.newBuilder()
                .setId(RuntimeIdGenerator.generateMessageId())
                .setType(ControlMessage.MessageType.TransferBlock)
                .setTransferBlockMsg(
                    ControlMessage.TransferBlockMsg.newBuilder()
                        .setRequestId(message.getId())
                        .setExecutorId(executorId)
                        .setBlockId(requestBlockMsg.getBlockId())
                        .setData(ByteString.copyFrom(SerializationUtils.serialize(dataToSerialize)))
                        .build())
                .build());
        break;
      default:
        throw new IllegalMessageException(
            new Exception("This message should not be requested to an executor :" + message.getType()));
      }
    }
  }

  private RuntimeAttribute convertBlockStoreType(final ControlMessage.BlockStore blockStoreType) {
    switch (blockStoreType) {
    case LOCAL:
      return RuntimeAttribute.Local;
    case MEMORY:
      return RuntimeAttribute.Memory;
    case FILE:
      return RuntimeAttribute.File;
    case MEMORY_FILE:
      return RuntimeAttribute.MemoryFile;
    case DISTRIBUTED_STORAGE:
      return RuntimeAttribute.DistributedStorage;
    default:
      throw new UnsupportedBlockStoreException(new Throwable("This block store is not yet supported"));
    }
  }
}