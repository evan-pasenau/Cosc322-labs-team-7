# COSC 322 Assignment 1 written questions

Name: Evan Pasenau
Student #: 36403509

---

## Part 1

- Demis Hassabis is the cofounder and CEO of DeepMind best known for leading breakthroughs in artificial intelligence including AlphaGo which was the first AI to beat a world champion at Go and AlphaFold an AI model that accurately predicts protein structures and was a major advance in both AI and biological science.

- Geoffrey Hinton is a British Canadian computer scientist regarded as one of the godfathers of AI for his foundational work on artificial neural networks and deep learning including back propagation and models like Boltzmann machines that underpin modern machine learning systems.

- John Hopfield pioneered early neural network models such as the Hopfield network which is a recurrent neural model inspired by physics that can store and recall patterns helping lay the theoretical groundwork for later deep learning.

- Judea Pearl is known for founding causal inference in AI introducing Bayesian networks and formal methods for reasoning about cause and effect which expanded how AI systems understand uncertainty, interventions, and explanations beyond pure correlation.

- Jonathan Schaeffer is a Canadian AI researcher whose work in computational game playing led to programs like Chinook the checkers AI that became a world champion showing how search and evaluation techniques can achieve expert level performance for challenging tasks.

- Jürgen Schmidhuber is a German computer scientist known for advancing deep learning and neural networks especially the development of Long Short Term Memory networks and meta learning concepts that were influential in sequence processing and modern AI research.
---

## Part 2 — Eight‑Puzzle and Gaschnig’s Heuristic

### 2.1 Best‑first search to calculate Gaschnig’s Distance — Implementation

**Implementation summary**
- Implemented a relaxed action factory that allows swapping the blank with any tile.
- Implemented f value logic for greedy best first and A* with the misplaced‑tile heuristic.
- Switched the test harness to use the relaxed action factory for Gaschnigs problem.

**Code locations**
- `src/main/java/cosc322/sliding_puzzle/ActionFactoryRelaxedNPuzzle.java`
- `src/main/java/cosc322/sliding_puzzle/StateSpaceNPuzzle.java`
- `src/main/java/cosc322/StateSpaceSearchTests.java`

### 2.2 Best‑first search to calculate Gaschnig’s Distance — Experiments

#### Q2.2.1 (10 points)
**Speculate on why the program runs in an infinite loop when you run it without changing anything. (Hint: look at the f‑values.)**

Before implementation, `StateSpaceNPuzzle.set_f_value(...)` returned the node’s default f value without setting it. The default in `SearchTreeNode` is `-999`, so every node had the same f value. The priority queue then could not prioritize progress and because the framework does not use a closed list to prevent revisiting states the relaxed puzzle generates cycles indefinitely. The tracer output shows repeated expansions with identical f values  which explains the apparent infinite loop.

#### Q2.2.2 (15 points)
**Discuss any interesting observations you make from your experiments.**

Start state:
```
3 6 0
2 7 4
1 8 5
```

**Observed results:**
- Greedy best first:
  - Solution length: 10
  - Nodes expanded: 81
- A*:
  - Solution length: 10
  - Nodes expanded: 1265
- Observation analysis:
  - In this case with this start state both the greedy and A* steached the same same goal solution of 10 steps but A* required a much larger search with more nodes then greedy.

**Discussion template:**
- Greedy best first typically expands fewer nodes but can return a longer not optimal path, since it ignores the path cost g and only follows h.
- A* uses g + h so it usually expands more nodes but returns the optimal Gaschnig distance in every case.
- The misplaced tile heuristic is admissible but weak so both methods can still explore many nodes for scrambled states.

---

### 2.3 General Questions

#### Q2.3.1 (5 points)
**Explain why Gaschnig’s heuristic is at least as accurate as the misplaced‑tile heuristic h1, and show situations where it is more accurate than both h1 and the Manhattan heuristic.**

Gaschnigs heuristic is the optimal number of swaps in the relaxed puzzle where the blank can swap with any tile. Each swap can place at most one misplaced tile into its correct position so any solution requires at least as many moves as the number of misplaced tiles. Therefore Gaschnigs heuristic is always >= h1.

Example where Gaschnig is strictly larger than both h1 and Manhattan:
```
Goal:  1 2 3      State: 2 1 3
       4 5 6             4 5 6
       7 8 0             7 8 0
```
- h1 = 2 (tiles 1 and 2 are misplaced)
- Manhattan = 2 (each of tiles 1 and 2 is one move from its goal)
- Gaschnig = 3 (sequence of swaps with blank requires three moves)

Therefore Gaschnig can be more accurate than both h1 and Manhattan.

#### Q2.3.2 (5 points)
**Can you suggest an algorithm to calculate Gaschnig’s heuristic efficiently without using state‑space search?**

Yes use the standard Gaschnig swap procedure:
1. If the blank is in its goal position swap it with any misplaced tile.
2. Otherwise swap the blank with the tile that belongs in the blanks current position.
3. Count swaps until the goal is reached.

This computes Gaschnigs distance directly in O(n) swaps without running search.

---


