package cosc322.sliding_puzzle;

import ygraph.ai.state_space_search.ActionFactory;
import ygraph.ai.state_space_search.sliding_puzzle.ActionSlidingPuzzle;
import ygraph.ai.state_space_search.sliding_puzzle.StateNPuzzle;
 
/**
 * ActionFactory for relaxed N-puzzles --- a tile A can be moved to Tile B as long as B is the 
 * blank. The solution to this relaxed puzzle is known as Gaschnig's heuristic.
 * 
 * For Assignment 01 in the COSC 322 class --- students need to implement this   
 *   
 */
public class ActionFactoryRelaxedNPuzzle extends ActionFactory<StateNPuzzle>{

	public ActionFactoryRelaxedNPuzzle() {
	}

	/**
	 * Returns an array of state.N actions  
	 */
	public ActionSlidingPuzzle[] getActions(StateNPuzzle state){					
		ActionSlidingPuzzle[] actions = new ActionSlidingPuzzle[state.N];
		
		for(int i = 0; i < state.N; i++){
			actions[i] = new ActionSlidingPuzzle(0, i + 1, state);
		}
		
		return actions;
	}
			
}//end of class
