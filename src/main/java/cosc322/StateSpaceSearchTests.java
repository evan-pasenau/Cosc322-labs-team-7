package cosc322;

import cosc322.sliding_puzzle.*;
import ygraph.ai.state_space_search.ActionFactory;
import ygraph.ai.state_space_search.BestFirstSearch;
import ygraph.ai.state_space_search.sliding_puzzle.StateNPuzzle;


public class StateSpaceSearchTests {
	
	public static void main(String[] args){
		int N = 8;
		int n = 3;
		
		int[][] g = {{0, 1, 2}, {3, 4, 5}, {6, 7, 8}};
		int[][] a = {{3, 6, 0}, {2, 7, 4}, {1, 8, 5}};

		StateNPuzzle goal = new StateNPuzzle(N, g);
		
		StateNPuzzle start = new StateNPuzzle(N, a);
		
		StateSpaceNPuzzle sp = new StateSpaceNPuzzle(N); 
		
		//ActionFactory for standard N-puzzle.      
		ActionFactory<StateNPuzzle> actionFac = new ActionFactoryRelaxedNPuzzle();
		sp.setActionFactory(actionFac);
				
		//Do greedy-best-first search
		sp.setGoalState(goal);
		sp.setInitialState((StateNPuzzle) start);
		StateSpaceSearchTests.greedyBestFirstSearch(sp);
		

		//Do A* search
		sp.setGoalState(goal);
		sp.setInitialState((StateNPuzzle) start);
		StateSpaceSearchTests.aStarSearch(sp);
		 
	}

	public static String greedyBestFirstSearch(StateSpaceNPuzzle sp) {
		sp.algorithm_type = BestFirstSearch.SEARCH_METHOD_GREEDY_BEST_FIRST;
		
		BestFirstSearch<StateNPuzzle> bfs = new BestFirstSearch<>(sp);
		String res = bfs.startSearchSession(true); // true to turn the tracer on
		System.out.println(res);
		return res;
		
	}
	
	public static String aStarSearch(StateSpaceNPuzzle sp) {
		sp.algorithm_type = BestFirstSearch.SEARCH_METHOD_A_STAR;
		
		BestFirstSearch<StateNPuzzle> bfs = new BestFirstSearch<>(sp);
		String res = bfs.startSearchSession(true); // true to turn the tracer on
		System.out.println(res);
		return res;	
	}
}
	
