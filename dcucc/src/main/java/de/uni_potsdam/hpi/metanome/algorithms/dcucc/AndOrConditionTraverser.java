package de.uni_potsdam.hpi.metanome.algorithms.dcucc;

import de.uni_potsdam.hpi.metanome.algorithm_helper.data_structures.ColumnCombinationBitset;
import de.uni_potsdam.hpi.metanome.algorithm_helper.data_structures.PositionListIndex;
import de.uni_potsdam.hpi.metanome.algorithm_integration.AlgorithmExecutionException;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author Jens Hildebrandt
 */
public class AndOrConditionTraverser extends OrConditionTraverser {

  Map<ColumnCombinationBitset, List<ConditionEntry>> singleConditions;

  public AndOrConditionTraverser(Dcucc algorithm) {
    super(algorithm);
  }

  @Override
  public void iterateConditionLattice(ColumnCombinationBitset partialUnique)
      throws AlgorithmExecutionException {
    singleConditions = new HashMap<>();
    Map<ColumnCombinationBitset, PositionListIndex> currentLevel = new HashMap<>();

    //calculate first level - initialisation
    for (ColumnCombinationBitset conditionColumn : this.algorithm.baseColumn) {
      //TODO better way to prune this columns
      if (partialUnique.containsColumn(conditionColumn.getSetBits().get(0))) {
        continue;
      }
      calculateCondition(partialUnique, currentLevel, conditionColumn,
                         this.algorithm.getPLI(conditionColumn));
    }

    currentLevel = apprioriGenerate(currentLevel);

    Map<ColumnCombinationBitset, PositionListIndex> nextLevel = new HashMap<>();
    while (!currentLevel.isEmpty()) {
      for (ColumnCombinationBitset potentialCondition : currentLevel.keySet()) {
        nextLevel.clear();
        calculateCondition(partialUnique, nextLevel, potentialCondition,
                           currentLevel.get(potentialCondition));
      }
      //TODO what if nextLevel is already empty?
      currentLevel = apprioriGenerate(nextLevel);
    }
    //return result
    LongArrayList touchedCluster = new LongArrayList();
    Long2LongOpenHashMap partialUniqueHash = this.algorithm.getPLI(partialUnique).asHashMap();
    for (ColumnCombinationBitset condition : this.singleConditions.keySet()) {
      List<LongArrayList> satisfiedCluster = new LinkedList();
      Long2ObjectOpenHashMap<LongArrayList> intersectingCluster = new Long2ObjectOpenHashMap<>();
      Long2ObjectOpenHashMap<ConditionEntry> clusterToEntryMap = new Long2ObjectOpenHashMap<>();
      //build intersecting cluster
      for (ConditionEntry singleCluster : this.singleConditions.get(condition)) {
        clusterToEntryMap.put(singleCluster.firstValue, singleCluster);
        satisfiedCluster.add(singleCluster.cluster);
        touchedCluster.clear();
        for (long rowNumber : singleCluster.cluster) {
          if (partialUniqueHash.containsKey(rowNumber)) {
            touchedCluster.add(partialUniqueHash.get(rowNumber));
          }
        }

        for (long partialUniqueClusterNumber : touchedCluster) {
          if (intersectingCluster.containsKey(partialUniqueClusterNumber)) {
            intersectingCluster.get(partialUniqueClusterNumber).add(singleCluster.cluster.get(0));
          } else {
            LongArrayList newConditionClusterNumbers = new LongArrayList();
            newConditionClusterNumbers.add(singleCluster.cluster.get(0));
            intersectingCluster.put(partialUniqueClusterNumber, newConditionClusterNumbers);
          }
        }
      }
      intersectingCluster = purgeIntersectingClusterEntries(intersectingCluster);


      List<LongArrayList>
          clustergroups =
          this.combineClusters(this.algorithm.frequency, satisfiedCluster, intersectingCluster);
      for (LongArrayList clusterNumbers : clustergroups) {
        Map<ColumnCombinationBitset, LongArrayList> conditionMap = new HashMap<>();
        for (long clusterNumber : clusterNumbers) {
          ConditionEntry entry = clusterToEntryMap.get(clusterNumber);
          if (conditionMap.containsKey(entry.condition)) {
            LongArrayList cluster = conditionMap.get(entry.condition);
            cluster.addAll(entry.cluster);
          } else {
            conditionMap.put(entry.condition, entry.cluster);
          }
          //this.algorithm.addConditionToResult(partialUnique, );
        }

        Condition resultCondition = new Condition(partialUnique, conditionMap);
      }
    }
  }

  @Override
  protected List<LongArrayList> combineClusters(int frequency,
                                                List<LongArrayList> satisfiedClusters,
                                                Long2ObjectOpenHashMap<LongArrayList> intersectingClusters) {
    List<LongArrayList> result = new LinkedList<>();
    LinkedList<ConditionTask> queue = new LinkedList();
    LongArrayList satisfiedClusterNumbers = new LongArrayList();
    long totalSize = 0;
    int i = 0;
    for (LongArrayList clusters : satisfiedClusters) {
      //satisfiedClusterNumbers.add(conditionMap.get(clusters.get(0)));
      satisfiedClusterNumbers.add(i);
      i++;
      totalSize = totalSize + clusters.size();
    }
    if (totalSize < frequency) {
      return result;
    }

    LongArrayList
        uniqueClusterNumbers =
        new LongArrayList(intersectingClusters.keySet().toLongArray());
    ConditionTask
        firstTask =
        new ConditionTask(0, satisfiedClusterNumbers, new LongArrayList(), totalSize);
    queue.add(firstTask);

    while (!queue.isEmpty()) {
      ConditionTask currentTask = queue.remove();

      if (currentTask.uniqueClusterNumber >= uniqueClusterNumbers.size()) {
        LongArrayList validCondition = new LongArrayList();
        validCondition.addAll(currentTask.conditionClusters);
        result.add(validCondition);
        continue;
      }
      for (long conditionCluster : currentTask.conditionClusters) {
        if (intersectingClusters.get(uniqueClusterNumbers.get(currentTask.uniqueClusterNumber))
            .contains(conditionCluster)) {
          ConditionTask newTask = currentTask.generateNextTask();
          if (newTask.remove(conditionCluster, satisfiedClusters.get((int) conditionCluster).size(),
                             frequency)) {
            queue.add(newTask);
          }
        }
      }
      for (long removedConditionCluster : currentTask.removedConditionClusters) {
        if (intersectingClusters.get((uniqueClusterNumbers.get(currentTask.uniqueClusterNumber)))
            .contains(removedConditionCluster)) {
          ConditionTask newTask = currentTask.generateNextTask();
          queue.add(newTask);
          break;
        }
      }
    }
    return result;
  }

  @Override
  protected void calculateCondition(ColumnCombinationBitset partialUnique,
                                    Map<ColumnCombinationBitset, PositionListIndex> currentLevel,
                                    ColumnCombinationBitset conditionColumn,
                                    PositionListIndex conditionPLI) throws
                                                                    AlgorithmExecutionException {
    List<LongArrayList> unsatisfiedClusters = new LinkedList<>();
    //check which conditions hold
    List<LongArrayList>
        conditions =
        this.calculateConditions(this.algorithm.getPLI(partialUnique),
                                 conditionPLI,
                                 this.algorithm.frequency,
                                 unsatisfiedClusters);

    if (!unsatisfiedClusters.isEmpty()) {
      currentLevel.put(conditionColumn, new PositionListIndex(unsatisfiedClusters));
    }
    Long2LongOpenHashMap conditionHashMap = conditionPLI.asHashMap();

    List<ConditionEntry> clusters = new LinkedList<>();
    for (LongArrayList cluster : conditions) {
      clusters
          .add(new ConditionEntry(conditionColumn, cluster));
    }

    if (clusters.isEmpty()) {
      return;
    }
    for (ColumnCombinationBitset singeConditionColumn : conditionColumn
        .getContainedOneColumnCombinations()) {
      List<ConditionEntry> existingCluster;
      if (singleConditions.containsKey(singeConditionColumn)) {
        existingCluster = singleConditions.get(singeConditionColumn);
      } else {
        existingCluster = new LinkedList<>();
        singleConditions.put(singeConditionColumn, existingCluster);
      }
      existingCluster.addAll(clusters);
    }

//    for (LongArrayList condition : conditions) {
//      this.algorithm.addConditionToResult(partialUnique, conditionColumn, condition);
//    }
  }

  public List<LongArrayList> calculateConditions(PositionListIndex partialUnique,
                                                 PositionListIndex PLICondition,
                                                 int frequency,
                                                 List<LongArrayList> unsatisfiedClusters) {
    List<LongArrayList> result = new LinkedList<>();
    Long2LongOpenHashMap uniqueHashMap = partialUnique.asHashMap();
    LongArrayList touchedClusters = new LongArrayList();
    nextCluster:
    for (LongArrayList cluster : PLICondition.getClusters()) {
      int unsatisfactionCount = 0;
      touchedClusters.clear();
      for (long rowNumber : cluster) {
        if (uniqueHashMap.containsKey(rowNumber)) {
          if (touchedClusters.contains(uniqueHashMap.get(rowNumber))) {
            unsatisfactionCount++;
          } else {
            touchedClusters.add(uniqueHashMap.get(rowNumber));
          }
        }
      }
      if (unsatisfactionCount == 0) {
        result.add(cluster);
      } else {
        if ((cluster.size() - unsatisfactionCount) >= frequency) {
          unsatisfiedClusters.add(cluster);
        }
      }
    }
    return result;
  }

  protected class ConditionEntry {

    public ColumnCombinationBitset condition;
    public LongArrayList cluster;

    public ConditionEntry(ColumnCombinationBitset condition, LongArrayList cluster) {
      this.condition = new ColumnCombinationBitset(condition);
      this.cluster = cluster.clone();
    }
  }
}