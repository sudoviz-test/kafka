/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.streams.processor.TaskId;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.apache.kafka.common.utils.Utils.filterMap;
import static org.apache.kafka.common.utils.Utils.union;

/**
 * All tasks contained by the Streams instance.
 *
 * Note that these tasks are shared between the TaskManager (stream thread) and the StateUpdater (restore thread),
 * i.e. all running active tasks are processed by the former and all restoring active tasks and standby tasks are
 * processed by the latter.
 */
class Tasks {
    private final Logger log;

    // TODO: convert to Stream/StandbyTask when we remove TaskManager#StateMachineTask with mocks
    private final Map<TaskId, Task> activeTasksPerId = new TreeMap<>();
    private final Map<TaskId, Task> standbyTasksPerId = new TreeMap<>();

    // Tasks may have been assigned for a NamedTopology that is not yet known by this host. When that occurs we stash
    // these unknown tasks until either the corresponding NamedTopology is added and we can create them at last, or
    // we receive a new assignment and they are revoked from the thread.
    private final Map<TaskId, Set<TopicPartition>> pendingActiveTasksToCreate = new HashMap<>();
    private final Map<TaskId, Set<TopicPartition>> pendingStandbyTasksToCreate = new HashMap<>();
    private final Map<TaskId, Set<TopicPartition>> pendingTasksToRecycle = new HashMap<>();
    private final Map<TaskId, Set<TopicPartition>> pendingTasksToUpdateInputPartitions = new HashMap<>();
    private final Set<Task> pendingTasksToInit = new HashSet<>();
    private final Set<TaskId> pendingTasksToClose = new HashSet<>();

    // TODO: convert to Stream/StandbyTask when we remove TaskManager#StateMachineTask with mocks
    private final Map<TopicPartition, Task> activeTasksPerPartition = new HashMap<>();

    Tasks(final LogContext logContext) {
        this.log = logContext.logger(getClass());
    }

    void clearPendingTasksToCreate() {
        pendingActiveTasksToCreate.clear();
        pendingStandbyTasksToCreate.clear();
    }

    Map<TaskId, Set<TopicPartition>> drainPendingActiveTasksForTopologies(final Set<String> currentTopologies) {
        final Map<TaskId, Set<TopicPartition>> pendingActiveTasksForTopologies =
            filterMap(pendingActiveTasksToCreate, t -> currentTopologies.contains(t.getKey().topologyName()));

        pendingActiveTasksToCreate.keySet().removeAll(pendingActiveTasksForTopologies.keySet());

        return pendingActiveTasksForTopologies;
    }

    Map<TaskId, Set<TopicPartition>> drainPendingStandbyTasksForTopologies(final Set<String> currentTopologies) {
        final Map<TaskId, Set<TopicPartition>> pendingActiveTasksForTopologies =
            filterMap(pendingStandbyTasksToCreate, t -> currentTopologies.contains(t.getKey().topologyName()));

        pendingStandbyTasksToCreate.keySet().removeAll(pendingActiveTasksForTopologies.keySet());

        return pendingActiveTasksForTopologies;
    }

    void addPendingActiveTasksToCreate(final Map<TaskId, Set<TopicPartition>> pendingTasks) {
        pendingActiveTasksToCreate.putAll(pendingTasks);
    }

    void addPendingStandbyTasksToCreate(final Map<TaskId, Set<TopicPartition>> pendingTasks) {
        pendingStandbyTasksToCreate.putAll(pendingTasks);
    }

    Set<TopicPartition> removePendingTaskToRecycle(final TaskId taskId) {
        return pendingTasksToRecycle.remove(taskId);
    }

    void addPendingTaskToRecycle(final TaskId taskId, final Set<TopicPartition> inputPartitions) {
        pendingTasksToRecycle.put(taskId, inputPartitions);
    }

    Set<TopicPartition> removePendingTaskToUpdateInputPartitions(final TaskId taskId) {
        return pendingTasksToUpdateInputPartitions.remove(taskId);
    }

    void addPendingTaskToUpdateInputPartitions(final TaskId taskId, final Set<TopicPartition> inputPartitions) {
        pendingTasksToUpdateInputPartitions.put(taskId, inputPartitions);
    }

    boolean removePendingTaskToClose(final TaskId taskId) {
        return pendingTasksToClose.remove(taskId);
    }

    void addPendingTaskToClose(final TaskId taskId) {
        pendingTasksToClose.add(taskId);
    }

    Set<Task> drainPendingTaskToInit() {
        final Set<Task> result = new HashSet<>(pendingTasksToInit);
        pendingTasksToInit.clear();
        return result;
    }

    void addPendingTaskToInit(final Collection<Task> tasks) {
        pendingTasksToInit.addAll(tasks);
    }

    void addNewActiveTasks(final Collection<Task> newTasks) {
        if (!newTasks.isEmpty()) {
            for (final Task activeTask : newTasks) {
                final TaskId taskId = activeTask.id();

                if (activeTasksPerId.containsKey(taskId)) {
                    throw new IllegalStateException("Attempted to create an active task that we already own: " + taskId);
                }

                if (standbyTasksPerId.containsKey(taskId)) {
                    throw new IllegalStateException("Attempted to create an active task while we already own its standby: " + taskId);
                }

                activeTasksPerId.put(activeTask.id(), activeTask);
                for (final TopicPartition topicPartition : activeTask.inputPartitions()) {
                    activeTasksPerPartition.put(topicPartition, activeTask);
                }
            }
        }
    }

    void addNewStandbyTasks(final Collection<Task> newTasks) {
        if (!newTasks.isEmpty()) {
            for (final Task standbyTask : newTasks) {
                final TaskId taskId = standbyTask.id();

                if (standbyTasksPerId.containsKey(taskId)) {
                    throw new IllegalStateException("Attempted to create an standby task that we already own: " + taskId);
                }

                if (activeTasksPerId.containsKey(taskId)) {
                    throw new IllegalStateException("Attempted to create an standby task while we already own its active: " + taskId);
                }

                standbyTasksPerId.put(standbyTask.id(), standbyTask);
            }
        }
    }

    void removeTask(final Task taskToRemove) {
        final TaskId taskId = taskToRemove.id();

        if (taskToRemove.state() != Task.State.CLOSED) {
            throw new IllegalStateException("Attempted to remove a task that is not closed: " + taskId);
        }

        if (taskToRemove.isActive()) {
            if (activeTasksPerId.remove(taskId) == null) {
                throw new IllegalArgumentException("Attempted to remove an active task that is not owned: " + taskId);
            }
            removePartitionsForActiveTask(taskId);
        } else {
            if (standbyTasksPerId.remove(taskId) == null) {
                throw new IllegalArgumentException("Attempted to remove a standby task that is not owned: " + taskId);
            }
        }
    }

    void replaceActiveWithStandby(final StandbyTask standbyTask) {
        final TaskId taskId = standbyTask.id();
        if (activeTasksPerId.remove(taskId) == null) {
            throw new IllegalStateException("Attempted to replace unknown active task with standby task: " + taskId);
        }
        removePartitionsForActiveTask(taskId);

        standbyTasksPerId.put(standbyTask.id(), standbyTask);
    }

    void replaceStandbyWithActive(final StreamTask activeTask) {
        final TaskId taskId = activeTask.id();
        if (standbyTasksPerId.remove(taskId) == null) {
            throw new IllegalStateException("Attempted to convert unknown standby task to stream task: " + taskId);
        }

        activeTasksPerId.put(activeTask.id(), activeTask);
        for (final TopicPartition topicPartition : activeTask.inputPartitions()) {
            activeTasksPerPartition.put(topicPartition, activeTask);
        }
    }

    boolean updateActiveTaskInputPartitions(final Task task, final Set<TopicPartition> topicPartitions) {
        final boolean requiresUpdate = !task.inputPartitions().equals(topicPartitions);
        if (requiresUpdate) {
            log.debug("Update task {} inputPartitions: current {}, new {}", task, task.inputPartitions(), topicPartitions);
            if (task.isActive()) {
                for (final TopicPartition inputPartition : task.inputPartitions()) {
                    activeTasksPerPartition.remove(inputPartition);
                }
                for (final TopicPartition topicPartition : topicPartitions) {
                    activeTasksPerPartition.put(topicPartition, task);
                }
            }
        }

        return requiresUpdate;
    }

    private void removePartitionsForActiveTask(final TaskId taskId) {
        final Set<TopicPartition> toBeRemoved = activeTasksPerPartition.entrySet().stream()
            .filter(e -> e.getValue().id().equals(taskId))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        toBeRemoved.forEach(activeTasksPerPartition::remove);
    }

    void clear() {
        activeTasksPerId.clear();
        standbyTasksPerId.clear();
        activeTasksPerPartition.clear();
    }

    // TODO: change return type to `StreamTask`
    Task activeTasksForInputPartition(final TopicPartition partition) {
        return activeTasksPerPartition.get(partition);
    }

    private Task getTask(final TaskId taskId) {
        if (activeTasksPerId.containsKey(taskId)) {
            return activeTasksPerId.get(taskId);
        }
        if (standbyTasksPerId.containsKey(taskId)) {
            return standbyTasksPerId.get(taskId);
        }
        return null;
    }

    Task task(final TaskId taskId) {
        final Task task = getTask(taskId);

        if (task != null)
            return task;
        else
            throw new IllegalStateException("Task unknown: " + taskId);
    }

    Collection<Task> tasks(final Collection<TaskId> taskIds) {
        final Set<Task> tasks = new HashSet<>();
        for (final TaskId taskId : taskIds) {
            tasks.add(task(taskId));
        }
        return tasks;
    }

    Collection<Task> activeTasks() {
        return Collections.unmodifiableCollection(activeTasksPerId.values());
    }

    /**
     * All tasks returned by any of the getters are read-only and should NOT be modified;
     * and the returned task could be modified by other threads concurrently
     */
    Set<Task> allTasks() {
        return union(HashSet::new, new HashSet<>(activeTasksPerId.values()), new HashSet<>(standbyTasksPerId.values()));
    }

    Set<TaskId> allTaskIds() {
        return union(HashSet::new, activeTasksPerId.keySet(), standbyTasksPerId.keySet());
    }

    Map<TaskId, Task> allTasksPerId() {
        final Map<TaskId, Task> ret = new HashMap<>();
        ret.putAll(activeTasksPerId);
        ret.putAll(standbyTasksPerId);
        return ret;
    }

    boolean owned(final TaskId taskId) {
        return getTask(taskId) != null;
    }

    // for testing only
    void addTask(final Task task) {
        if (task.isActive()) {
            activeTasksPerId.put(task.id(), task);
        } else {
            standbyTasksPerId.put(task.id(), task);
        }
    }
}
