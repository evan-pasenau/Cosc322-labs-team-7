package cosc322.sliding_puzzle;
 
import ygraph.ai.state_space_search.ActionFactory;
import ygraph.ai.state_space_search.sliding_puzzle.ActionSlidingPuzzle;
import ygraph.ai.state_space_search.sliding_puzzle.StateNPuzzle;
 
/**
 * ActionFactory for standard N-puzzles, provided as an example for COSC 322 students
 *  
 * @author yong.gao@ubc.ca
 */
public class ActionFactoryNPuzzle extends ActionFactory<StateNPuzzle>{
 
/**
 * Get the list of all possible actions applicable to the given state 
 * for a standard N-puzzle game. 
 * 
 * @return an array of size 4. Number of non-null entries can be 2, 3, or 4.  	
 */
public ActionSlidingPuzzle[] getActions(StateNPuzzle state){
	   
	ActionSlidingPuzzle[] actions = new ActionSlidingPuzzle[4];
   
	int[] blankPos = state.blankPosition();
			
	if(blankPos[1] > 0){ // move left
		int leftTileID = state.tile_ID_at(blankPos[0], blankPos[1] - 1);
		actions[0] = new ActionSlidingPuzzle(0, leftTileID, state); 
		//numOfActions++;
	}
	
	if(blankPos[1] < state.n - 1){ // move right
		int rightTileID = state.tile_ID_at(blankPos[0], blankPos[1] + 1);
		actions[1] = new ActionSlidingPuzzle(0, rightTileID, state); 
		//numOfActions++;
	}
	
	
	if(blankPos[0] > 0){ // move up
		int upTileID = state.tile_ID_at(blankPos[0] - 1, blankPos[1]);
		actions[2] = new ActionSlidingPuzzle(0, upTileID, state);
		//numOfActions++;
	}
	
	if(blankPos[0] < state.n - 1){ // move down
		int downTileID = state.tile_ID_at(blankPos[0] + 1, blankPos[1]);
		actions[3] = new ActionSlidingPuzzle(0, downTileID, state); 
		//numOfActions++;
	}
	
	return actions;	
   }
}//end of class
