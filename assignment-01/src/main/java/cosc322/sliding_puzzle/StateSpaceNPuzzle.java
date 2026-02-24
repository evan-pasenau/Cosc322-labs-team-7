package cosc322.sliding_puzzle;

import ygraph.ai.state_space_search.*;
import ygraph.ai.state_space_search.sliding_puzzle.StateNPuzzle;
 
/**
 * Models the state space of an N-puzzle
 * 
 * @author Yong Gao (yong.gao@ubc.ca)
 * Jan 4, 2021
 */
public class StateSpaceNPuzzle extends StateSpace<StateNPuzzle>{
	
	public static final int HEURISTC_TYPE_MISPLACED_TILE = 1;
	public static final int HEURISTIC_TYPE_STREET_BLOCK = 2;  

	private int N = 0;
	private int n = 0;
	 	
	/**
	 * Create an N-puzzle state space  
	 * @param N 
	 */
	public StateSpaceNPuzzle(int N){   
		this.N = N;
		this.n = (int) Math.sqrt(this.N + 1.0);
	}
	
	/**
	 * Create an N-puzzle state space with a concrete Action Factory  
	 * @param N
	 * @param actionFac
	 */
	public StateSpaceNPuzzle(int N, ActionFactory<StateNPuzzle> actionFac){
		this(N);
		super.actFac = actionFac;
	}
	
 
	@Override 
	public double set_f_value(SearchTreeNode<StateNPuzzle> node) {
 
		//For Assignment 01, you need to  complete the implementation of 
		//this method to calculate and set the f_value of the given node
		if(this.algorithm_type == BestFirstSearch.SEARCH_METHOD_A_STAR){
			node.set_f_value(node.get_g_value() + compute_h_value(node.getState()));
		}else if(this.algorithm_type == BestFirstSearch.SEARCH_METHOD_GREEDY_BEST_FIRST){
			node.set_f_value(compute_h_value(node.getState()));
		}
		 
		return node.get_f_value();
	}
	
	public boolean goalTest(SearchTreeNode<StateNPuzzle> node) {
		StateNPuzzle state = node.getState();
		
		for(int i = 0; i < n; i++){
			for(int j = 0; j < n; j++){
			   if( state.tile_ID_at(i, j) != this.goal.tile_ID_at(i, j)){
				  return false;
			   }
			}
		}
		
		return true;
	}
	
	@Override
	public boolean goalTest(StateNPuzzle state) {
		
		for(int i = 0; i < n; i++){
			for(int j = 0; j < n; j++){
			   if( state.tile_ID_at(i, j) != this.goal.tile_ID_at(i, j)){
				  return false;
			   }
			}
		}
		
		return true;
	}
	
	
	@Override
	public double compute_h_value(StateNPuzzle state) { 				  		
		double h = 0;		 
		
		h = this.misplacedTileDistance(state); 
		return h;
	}

	/**
	 * Compute the heuristic value (h-value) of the given N-puzzle state, using the given 
	 * type of heuristic  
	 * @param state an N-puzzle state
	 * @param heuristicType the type of the heuristic function to be used   
	 * @return
	 */
	public double compute_h_value(StateNPuzzle state, int heuristicType){
		double h = 0;
		
		if(heuristicType == StateSpaceNPuzzle.HEURISTC_TYPE_MISPLACED_TILE){
			h = this.misplacedTileDistance(state);
		}
		else if(heuristicType == StateSpaceNPuzzle.HEURISTIC_TYPE_STREET_BLOCK){
			h = this.streetBlockDistance(state);
		}
		
		return h;
	}
	

	/**
	 * The misplaced tile distance
	 * @param state the current state
	 * @return the misplaced-tile distance to the goal state 
	 */
	public int misplacedTileDistance(StateNPuzzle state){
		int h = 0;
		
		for(int i = 0; i < state.n; i++){
			for(int j = 0; j < state.n; j++){
				if((state.tile_ID_at(i, j) != 0) && (state.tile_ID_at(i, j) != this.goal.tile_ID_at(i, j))){
					h = h + 1;
				}
			}
		}		
		//System.out.println("h = " + h +"+++++ \n" + state);
		return h;	
	}
	
	/**
	 * 
	 * @param state the current state 
	 * @return the street-block distance to the goal state 
	 */
	public int streetBlockDistance(StateNPuzzle state){
		int h = 0;
		
		for(int i = 1; i < state.N + 1; i++){
			int[] pos = state.tile_position(i);
			int[] goalPos = this.goal.tile_position(i);
			
			h = h + Math.abs(pos[0] - goalPos[0]) + Math.abs(pos[1] - goalPos[1]);			
		}
		
		return h;
	}
	 	
}//end of class
