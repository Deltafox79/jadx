package jadx.core.dex.visitors.regions;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.core.dex.attributes.AFlag;
import jadx.core.dex.attributes.AType;
import jadx.core.dex.attributes.nodes.EdgeInsnAttr;
import jadx.core.dex.attributes.nodes.LoopInfo;
import jadx.core.dex.attributes.nodes.LoopLabelAttr;
import jadx.core.dex.instructions.IfNode;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.SwitchNode;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.Edge;
import jadx.core.dex.nodes.IBlock;
import jadx.core.dex.nodes.IContainer;
import jadx.core.dex.nodes.IRegion;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.dex.regions.Region;
import jadx.core.dex.regions.SwitchRegion;
import jadx.core.dex.regions.SynchronizedRegion;
import jadx.core.dex.regions.conditions.IfInfo;
import jadx.core.dex.regions.conditions.IfRegion;
import jadx.core.dex.regions.loops.LoopRegion;
import jadx.core.dex.trycatch.ExcHandlerAttr;
import jadx.core.dex.trycatch.ExceptionHandler;
import jadx.core.dex.trycatch.SplitterBlockAttr;
import jadx.core.dex.trycatch.TryCatchBlock;
import jadx.core.utils.BlockUtils;
import jadx.core.utils.ErrorsCounter;
import jadx.core.utils.InsnRemover;
import jadx.core.utils.RegionUtils;
import jadx.core.utils.exceptions.JadxOverflowException;
import jadx.core.utils.exceptions.JadxRuntimeException;

import static jadx.core.dex.visitors.regions.IfMakerHelper.confirmMerge;
import static jadx.core.dex.visitors.regions.IfMakerHelper.makeIfInfo;
import static jadx.core.dex.visitors.regions.IfMakerHelper.mergeNestedIfNodes;
import static jadx.core.dex.visitors.regions.IfMakerHelper.searchNestedIf;
import static jadx.core.utils.BlockUtils.getNextBlock;
import static jadx.core.utils.BlockUtils.isPathExists;
import static jadx.core.utils.BlockUtils.skipSyntheticSuccessor;

public class RegionMaker {
	private static final Logger LOG = LoggerFactory.getLogger(RegionMaker.class);

	private final MethodNode mth;
	private final int regionsLimit;
	private int regionsCount;
	private BitSet processedBlocks;

	public RegionMaker(MethodNode mth) {
		this.mth = mth;
		int blocksCount = mth.getBasicBlocks().size();
		this.processedBlocks = new BitSet(blocksCount);
		this.regionsLimit = blocksCount * 100;
	}

	public Region makeRegion(BlockNode startBlock, RegionStack stack) {
		Region r = new Region(stack.peekRegion());
		if (startBlock == null) {
			return r;
		}

		int startBlockId = startBlock.getId();
		if (processedBlocks.get(startBlockId)) {
			mth.addWarn("Removed duplicated region for block: " + startBlock + ' ' + startBlock.getAttributesString());
			return r;
		}
		processedBlocks.set(startBlockId);

		BlockNode next = startBlock;
		while (next != null) {
			next = traverse(r, next, stack);
			regionsCount++;
			if (regionsCount > regionsLimit) {
				throw new JadxOverflowException("Regions count limit reached");
			}
		}
		return r;
	}

	/**
	 * Recursively traverse all blocks from 'block' until block from 'exits'
	 */
	private BlockNode traverse(IRegion r, BlockNode block, RegionStack stack) {
		BlockNode next = null;
		boolean processed = false;

		List<LoopInfo> loops = block.getAll(AType.LOOP);
		int loopCount = loops.size();
		if (loopCount != 0 && block.contains(AFlag.LOOP_START)) {
			if (loopCount == 1) {
				next = processLoop(r, loops.get(0), stack);
				processed = true;
			} else {
				for (LoopInfo loop : loops) {
					if (loop.getStart() == block) {
						next = processLoop(r, loop, stack);
						processed = true;
						break;
					}
				}
			}
		}

		InsnNode insn = BlockUtils.getLastInsn(block);
		if (!processed && insn != null) {
			switch (insn.getType()) {
				case IF:
					next = processIf(r, block, (IfNode) insn, stack);
					processed = true;
					break;

				case SWITCH:
					next = processSwitch(r, block, (SwitchNode) insn, stack);
					processed = true;
					break;

				case MONITOR_ENTER:
					next = processMonitorEnter(r, block, insn, stack);
					processed = true;
					break;

				default:
					break;
			}
		}
		if (!processed) {
			r.getSubBlocks().add(block);
			next = getNextBlock(block);
		}
		if (next != null && !stack.containsExit(block) && !stack.containsExit(next)) {
			return next;
		}
		return null;
	}

	private BlockNode processLoop(IRegion curRegion, LoopInfo loop, RegionStack stack) {
		BlockNode loopStart = loop.getStart();
		Set<BlockNode> exitBlocksSet = loop.getExitNodes();

		// set exit blocks scan order priority
		// this can help if loop have several exits (after using 'break' or 'return' in loop)
		List<BlockNode> exitBlocks = new ArrayList<>(exitBlocksSet.size());
		BlockNode nextStart = getNextBlock(loopStart);
		if (nextStart != null && exitBlocksSet.remove(nextStart)) {
			exitBlocks.add(nextStart);
		}
		if (exitBlocksSet.remove(loopStart)) {
			exitBlocks.add(loopStart);
		}
		if (exitBlocksSet.remove(loop.getEnd())) {
			exitBlocks.add(loop.getEnd());
		}
		exitBlocks.addAll(exitBlocksSet);

		LoopRegion loopRegion = makeLoopRegion(curRegion, loop, exitBlocks);
		if (loopRegion == null) {
			BlockNode exit = makeEndlessLoop(curRegion, stack, loop, loopStart);
			insertContinue(loop);
			return exit;
		}
		curRegion.getSubBlocks().add(loopRegion);
		IRegion outerRegion = stack.peekRegion();
		stack.push(loopRegion);

		IfInfo condInfo = makeIfInfo(loopRegion.getHeader());
		condInfo = searchNestedIf(condInfo);
		confirmMerge(condInfo);
		if (!loop.getLoopBlocks().contains(condInfo.getThenBlock())) {
			// invert loop condition if 'then' points to exit
			condInfo = IfInfo.invert(condInfo);
		}
		loopRegion.setCondition(condInfo.getCondition());
		exitBlocks.removeAll(condInfo.getMergedBlocks());

		if (!exitBlocks.isEmpty()) {
			BlockNode loopExit = condInfo.getElseBlock();
			if (loopExit != null) {
				// add 'break' instruction before path cross between main loop exit and sub-exit
				for (Edge exitEdge : loop.getExitEdges()) {
					if (exitBlocks.contains(exitEdge.getSource())) {
						insertLoopBreak(stack, loop, loopExit, exitEdge);
					}
				}
			}
		}

		BlockNode out;
		if (loopRegion.isConditionAtEnd()) {
			BlockNode thenBlock = condInfo.getThenBlock();
			out = thenBlock == loopStart ? condInfo.getElseBlock() : thenBlock;
			loopStart.remove(AType.LOOP);
			loop.getEnd().add(AFlag.ADDED_TO_REGION);
			stack.addExit(loop.getEnd());
			processedBlocks.clear(loopStart.getId());
			Region body = makeRegion(loopStart, stack);
			loopRegion.setBody(body);
			loopStart.addAttr(AType.LOOP, loop);
			loop.getEnd().remove(AFlag.ADDED_TO_REGION);
		} else {
			out = condInfo.getElseBlock();
			if (outerRegion != null
					&& out.contains(AFlag.LOOP_START)
					&& !out.getAll(AType.LOOP).contains(loop)
					&& RegionUtils.isRegionContainsBlock(outerRegion, out)) {
				// exit to already processed outer loop
				out = null;
			}
			stack.addExit(out);
			BlockNode loopBody = condInfo.getThenBlock();
			Region body = makeRegion(loopBody, stack);
			// add blocks from loop start to first condition block
			BlockNode conditionBlock = condInfo.getIfBlock();
			if (loopStart != conditionBlock) {
				Set<BlockNode> blocks = BlockUtils.getAllPathsBlocks(loopStart, conditionBlock);
				blocks.remove(conditionBlock);
				for (BlockNode block : blocks) {
					if (block.getInstructions().isEmpty()
							&& !block.contains(AFlag.ADDED_TO_REGION)
							&& !RegionUtils.isRegionContainsBlock(body, block)) {
						body.add(block);
					}
				}
			}
			loopRegion.setBody(body);
		}
		stack.pop();
		insertContinue(loop);
		return out;
	}

	/**
	 * Select loop exit and construct LoopRegion
	 */
	private LoopRegion makeLoopRegion(IRegion curRegion, LoopInfo loop, List<BlockNode> exitBlocks) {
		for (BlockNode block : exitBlocks) {
			if (block.contains(AType.EXC_HANDLER)) {
				continue;
			}
			InsnNode lastInsn = BlockUtils.getLastInsn(block);
			if (lastInsn == null || lastInsn.getType() != InsnType.IF) {
				continue;
			}
			List<LoopInfo> loops = block.getAll(AType.LOOP);
			if (!loops.isEmpty() && loops.get(0) != loop) {
				// skip nested loop condition
				continue;
			}
			LoopRegion loopRegion = new LoopRegion(curRegion, loop, block, block == loop.getEnd());
			boolean found;
			if (block == loop.getStart() || block == loop.getEnd()
					|| BlockUtils.isEmptySimplePath(loop.getStart(), block)) {
				found = true;
			} else if (block.getPredecessors().contains(loop.getStart())) {
				loopRegion.setPreCondition(loop.getStart());
				// if we can't merge pre-condition this is not correct header
				found = loopRegion.checkPreCondition();
			} else {
				found = false;
			}
			if (found) {
				List<LoopInfo> list = mth.getAllLoopsForBlock(block);
				if (list.size() >= 2) {
					// bad condition if successors going out of all loops
					boolean allOuter = true;
					for (BlockNode outerBlock : block.getCleanSuccessors()) {
						List<LoopInfo> outLoopList = mth.getAllLoopsForBlock(outerBlock);
						outLoopList.remove(loop);
						if (!outLoopList.isEmpty()) {
							// goes to outer loop
							allOuter = false;
							break;
						}
					}
					if (allOuter) {
						found = false;
					}
				}
			}
			if (found && !checkLoopExits(loop, block)) {
				found = false;
			}
			if (found) {
				return loopRegion;
			}
		}
		// no exit found => endless loop
		return null;
	}

	private boolean checkLoopExits(LoopInfo loop, BlockNode mainExitBlock) {
		List<Edge> exitEdges = loop.getExitEdges();
		if (exitEdges.size() < 2) {
			return true;
		}
		Optional<Edge> mainEdgeOpt = exitEdges.stream().filter(edge -> edge.getSource() == mainExitBlock).findFirst();
		if (!mainEdgeOpt.isPresent()) {
			throw new JadxRuntimeException("Not found exit edge by exit block: " + mainExitBlock);
		}
		Edge mainExitEdge = mainEdgeOpt.get();
		BlockNode mainOutBlock = skipSyntheticSuccessor(mainExitEdge.getTarget());
		for (Edge exitEdge : exitEdges) {
			if (exitEdge != mainExitEdge) {
				BlockNode outBlock = skipSyntheticSuccessor(exitEdge.getTarget());
				// all exit paths must be same or don't cross (will be inside loop)
				if (!isEqualPaths(mainOutBlock, outBlock)) {
					BlockNode crossBlock = BlockUtils.getPathCross(mth, mainOutBlock, outBlock);
					if (crossBlock != null) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private BlockNode makeEndlessLoop(IRegion curRegion, RegionStack stack, LoopInfo loop, BlockNode loopStart) {
		LoopRegion loopRegion = new LoopRegion(curRegion, loop, null, false);
		curRegion.getSubBlocks().add(loopRegion);

		loopStart.remove(AType.LOOP);
		processedBlocks.clear(loopStart.getId());
		stack.push(loopRegion);

		BlockNode out = null;
		// insert 'break' for exits
		List<Edge> exitEdges = loop.getExitEdges();
		if (exitEdges.size() == 1) {
			Edge exitEdge = exitEdges.get(0);
			BlockNode exit = exitEdge.getTarget();
			if (insertLoopBreak(stack, loop, exit, exitEdge)) {
				BlockNode nextBlock = getNextBlock(exit);
				if (nextBlock != null) {
					stack.addExit(nextBlock);
					out = nextBlock;
				}
			}
		} else {
			for (Edge exitEdge : exitEdges) {
				BlockNode exit = exitEdge.getTarget();
				List<BlockNode> blocks = BlockUtils.bitSetToBlocks(mth, exit.getDomFrontier());
				for (BlockNode block : blocks) {
					if (BlockUtils.isPathExists(exit, block)) {
						stack.addExit(block);
						insertLoopBreak(stack, loop, block, exitEdge);
						out = block;
					} else {
						insertLoopBreak(stack, loop, exit, exitEdge);
					}
				}
			}
		}

		Region body = makeRegion(loopStart, stack);
		BlockNode loopEnd = loop.getEnd();
		if (!RegionUtils.isRegionContainsBlock(body, loopEnd)
				&& !loopEnd.contains(AType.EXC_HANDLER)
				&& !inExceptionHandlerBlocks(loopEnd)) {
			body.getSubBlocks().add(loopEnd);
		}
		loopRegion.setBody(body);

		if (out == null) {
			BlockNode next = getNextBlock(loopEnd);
			out = RegionUtils.isRegionContainsBlock(body, next) ? null : next;
		}
		stack.pop();
		loopStart.addAttr(AType.LOOP, loop);
		return out;
	}

	private boolean inExceptionHandlerBlocks(BlockNode loopEnd) {
		if (mth.getExceptionHandlersCount() == 0) {
			return false;
		}
		for (ExceptionHandler eh : mth.getExceptionHandlers()) {
			if (eh.getBlocks().contains(loopEnd)) {
				return true;
			}
		}
		return false;
	}

	private boolean canInsertBreak(BlockNode exit) {
		if (exit.contains(AFlag.RETURN)
				|| BlockUtils.checkLastInsnType(exit, InsnType.BREAK)) {
			return false;
		}
		List<BlockNode> simplePath = BlockUtils.buildSimplePath(exit);
		if (!simplePath.isEmpty()) {
			BlockNode lastBlock = simplePath.get(simplePath.size() - 1);
			if (lastBlock.contains(AFlag.RETURN)
					|| lastBlock.getSuccessors().isEmpty()) {
				return false;
			}
		}
		// check if there no outer switch (TODO: very expensive check)
		Set<BlockNode> paths = BlockUtils.getAllPathsBlocks(mth.getEnterBlock(), exit);
		for (BlockNode block : paths) {
			if (BlockUtils.checkLastInsnType(block, InsnType.SWITCH)) {
				return false;
			}
		}
		return true;
	}

	private boolean insertLoopBreak(RegionStack stack, LoopInfo loop, BlockNode loopExit, Edge exitEdge) {
		BlockNode exit = exitEdge.getTarget();
		BlockNode insertBlock = null;
		boolean confirm = false;
		// process special cases
		if (loopExit == exit) {
			// try/catch at loop end
			BlockNode source = exitEdge.getSource();
			if (source.contains(AType.CATCH_BLOCK)
					&& source.getSuccessors().size() == 2) {
				BlockNode other = BlockUtils.selectOther(loopExit, source.getSuccessors());
				if (other != null) {
					other = BlockUtils.skipSyntheticSuccessor(other);
					if (other.contains(AType.EXC_HANDLER)) {
						insertBlock = source;
						confirm = true;
					}
				}
			}
		}
		if (!confirm) {
			while (exit != null) {
				if (insertBlock != null && isPathExists(loopExit, exit)) {
					// found cross
					if (canInsertBreak(insertBlock)) {
						confirm = true;
						break;
					}
					return false;
				}
				insertBlock = exit;
				List<BlockNode> cs = exit.getCleanSuccessors();
				exit = cs.size() == 1 ? cs.get(0) : null;
			}
		}
		if (!confirm) {
			return false;
		}
		InsnNode breakInsn = new InsnNode(InsnType.BREAK, 0);
		breakInsn.addAttr(AType.LOOP, loop);
		EdgeInsnAttr.addEdgeInsn(insertBlock, insertBlock.getSuccessors().get(0), breakInsn);
		stack.addExit(exit);
		// add label to 'break' if needed
		addBreakLabel(exitEdge, exit, breakInsn);
		return true;
	}

	private void addBreakLabel(Edge exitEdge, BlockNode exit, InsnNode breakInsn) {
		BlockNode outBlock = BlockUtils.getNextBlock(exitEdge.getTarget());
		if (outBlock == null) {
			return;
		}
		List<LoopInfo> exitLoop = mth.getAllLoopsForBlock(outBlock);
		if (!exitLoop.isEmpty()) {
			return;
		}
		List<LoopInfo> inLoops = mth.getAllLoopsForBlock(exitEdge.getSource());
		if (inLoops.size() < 2) {
			return;
		}
		// search for parent loop
		LoopInfo parentLoop = null;
		for (LoopInfo loop : inLoops) {
			if (loop.getParentLoop() == null) {
				parentLoop = loop;
				break;
			}
		}
		if (parentLoop == null) {
			return;
		}
		if (parentLoop.getEnd() != exit && !parentLoop.getExitNodes().contains(exit)) {
			LoopLabelAttr labelAttr = new LoopLabelAttr(parentLoop);
			breakInsn.addAttr(labelAttr);
			parentLoop.getStart().addAttr(labelAttr);
		}
	}

	private static void insertContinue(LoopInfo loop) {
		BlockNode loopEnd = loop.getEnd();
		List<BlockNode> predecessors = loopEnd.getPredecessors();
		if (predecessors.size() <= 1) {
			return;
		}
		Set<BlockNode> loopExitNodes = loop.getExitNodes();
		for (BlockNode pred : predecessors) {
			if (canInsertContinue(pred, predecessors, loopEnd, loopExitNodes)) {
				InsnNode cont = new InsnNode(InsnType.CONTINUE, 0);
				pred.getInstructions().add(cont);
			}
		}
	}

	private static boolean canInsertContinue(BlockNode pred, List<BlockNode> predecessors, BlockNode loopEnd,
			Set<BlockNode> loopExitNodes) {
		if (!pred.contains(AFlag.SYNTHETIC)
				|| BlockUtils.checkLastInsnType(pred, InsnType.CONTINUE)) {
			return false;
		}
		List<BlockNode> preds = pred.getPredecessors();
		if (preds.isEmpty()) {
			return false;
		}
		BlockNode codePred = preds.get(0);
		if (codePred.contains(AFlag.ADDED_TO_REGION)) {
			return false;
		}
		if (loopEnd.isDominator(codePred)
				|| loopExitNodes.contains(codePred)) {
			return false;
		}
		if (isDominatedOnBlocks(codePred, predecessors)) {
			return false;
		}
		boolean gotoExit = false;
		for (BlockNode exit : loopExitNodes) {
			if (BlockUtils.isPathExists(codePred, exit)) {
				gotoExit = true;
				break;
			}
		}
		return gotoExit;
	}

	private static boolean isDominatedOnBlocks(BlockNode dom, List<BlockNode> blocks) {
		for (BlockNode node : blocks) {
			if (!node.isDominator(dom)) {
				return false;
			}
		}
		return true;
	}

	private BlockNode processMonitorEnter(IRegion curRegion, BlockNode block, InsnNode insn, RegionStack stack) {
		SynchronizedRegion synchRegion = new SynchronizedRegion(curRegion, insn);
		synchRegion.getSubBlocks().add(block);
		curRegion.getSubBlocks().add(synchRegion);

		Set<BlockNode> exits = new HashSet<>();
		Set<BlockNode> cacheSet = new HashSet<>();
		traverseMonitorExits(synchRegion, insn.getArg(0), block, exits, cacheSet);

		for (InsnNode exitInsn : synchRegion.getExitInsns()) {
			BlockNode insnBlock = BlockUtils.getBlockByInsn(mth, exitInsn);
			if (insnBlock != null) {
				insnBlock.add(AFlag.DONT_GENERATE);
			}
			exitInsn.add(AFlag.DONT_GENERATE);
			exitInsn.add(AFlag.REMOVE);
			InsnRemover.unbindInsn(mth, exitInsn);
		}

		BlockNode body = getNextBlock(block);
		if (body == null) {
			ErrorsCounter.methodWarn(mth, "Unexpected end of synchronized block");
			return null;
		}
		BlockNode exit = null;
		if (exits.size() == 1) {
			exit = getNextBlock(exits.iterator().next());
		} else if (exits.size() > 1) {
			cacheSet.clear();
			exit = traverseMonitorExitsCross(body, exits, cacheSet);
		}

		stack.push(synchRegion);
		if (exit != null) {
			stack.addExit(exit);
		} else {
			for (BlockNode exitBlock : exits) {
				// don't add exit blocks which leads to method end blocks ('return', 'throw', etc)
				List<BlockNode> list = BlockUtils.buildSimplePath(exitBlock);
				if (list.isEmpty() || !list.get(list.size() - 1).getSuccessors().isEmpty()) {
					stack.addExit(exitBlock);
				}
			}
		}
		synchRegion.getSubBlocks().add(makeRegion(body, stack));
		stack.pop();
		return exit;
	}

	/**
	 * Traverse from monitor-enter thru successors and collect blocks contains monitor-exit
	 */
	private static void traverseMonitorExits(SynchronizedRegion region, InsnArg arg, BlockNode block, Set<BlockNode> exits,
			Set<BlockNode> visited) {
		visited.add(block);
		for (InsnNode insn : block.getInstructions()) {
			if (insn.getType() == InsnType.MONITOR_EXIT
					&& insn.getArg(0).equals(arg)) {
				exits.add(block);
				region.getExitInsns().add(insn);
				return;
			}
		}
		for (BlockNode node : block.getSuccessors()) {
			if (!visited.contains(node)) {
				traverseMonitorExits(region, arg, node, exits, visited);
			}
		}
	}

	/**
	 * Traverse from monitor-enter thru successors and search for exit paths cross
	 */
	private static BlockNode traverseMonitorExitsCross(BlockNode block, Set<BlockNode> exits, Set<BlockNode> visited) {
		visited.add(block);
		for (BlockNode node : block.getCleanSuccessors()) {
			boolean cross = true;
			for (BlockNode exitBlock : exits) {
				boolean p = isPathExists(exitBlock, node);
				if (!p) {
					cross = false;
					break;
				}
			}
			if (cross) {
				return node;
			}
			if (!visited.contains(node)) {
				BlockNode res = traverseMonitorExitsCross(node, exits, visited);
				if (res != null) {
					return res;
				}
			}
		}
		return null;
	}

	private BlockNode processIf(IRegion currentRegion, BlockNode block, IfNode ifnode, RegionStack stack) {
		if (block.contains(AFlag.ADDED_TO_REGION)) {
			// block already included in other 'if' region
			return ifnode.getThenBlock();
		}

		IfInfo currentIf = makeIfInfo(block);
		IfInfo mergedIf = mergeNestedIfNodes(currentIf);
		if (mergedIf != null) {
			currentIf = mergedIf;
		} else {
			// invert simple condition (compiler often do it)
			currentIf = IfInfo.invert(currentIf);
		}
		IfInfo modifiedIf = IfMakerHelper.restructureIf(mth, block, currentIf);
		if (modifiedIf != null) {
			currentIf = modifiedIf;
		} else {
			if (currentIf.getMergedBlocks().size() <= 1) {
				return null;
			}
			currentIf = makeIfInfo(block);
			currentIf = IfMakerHelper.restructureIf(mth, block, currentIf);
			if (currentIf == null) {
				// all attempts failed
				return null;
			}
		}
		confirmMerge(currentIf);

		IfRegion ifRegion = new IfRegion(currentRegion);
		ifRegion.setCondition(currentIf.getCondition());
		ifRegion.setConditionBlocks(currentIf.getMergedBlocks());
		currentRegion.getSubBlocks().add(ifRegion);

		BlockNode outBlock = currentIf.getOutBlock();
		stack.push(ifRegion);
		stack.addExit(outBlock);

		ifRegion.setThenRegion(makeRegion(currentIf.getThenBlock(), stack));
		BlockNode elseBlock = currentIf.getElseBlock();
		if (elseBlock == null || stack.containsExit(elseBlock)) {
			ifRegion.setElseRegion(null);
		} else {
			ifRegion.setElseRegion(makeRegion(elseBlock, stack));
		}

		// insert edge insns in new 'else' branch
		// TODO: make more common algorithm
		if (ifRegion.getElseRegion() == null && outBlock != null) {
			List<EdgeInsnAttr> edgeInsnAttrs = outBlock.getAll(AType.EDGE_INSN);
			if (!edgeInsnAttrs.isEmpty()) {
				Region elseRegion = new Region(ifRegion);
				for (EdgeInsnAttr edgeInsnAttr : edgeInsnAttrs) {
					if (edgeInsnAttr.getEnd().equals(outBlock)) {
						addEdgeInsn(currentIf, elseRegion, edgeInsnAttr);
					}
				}
				ifRegion.setElseRegion(elseRegion);
			}
		}

		stack.pop();
		return outBlock;
	}

	private void addEdgeInsn(IfInfo ifInfo, Region region, EdgeInsnAttr edgeInsnAttr) {
		BlockNode start = edgeInsnAttr.getStart();
		if (start.contains(AFlag.ADDED_TO_REGION)) {
			return;
		}
		boolean fromThisIf = false;
		for (BlockNode ifBlock : ifInfo.getMergedBlocks()) {
			if (ifBlock.getSuccessors().contains(start)) {
				fromThisIf = true;
				break;
			}
		}
		if (!fromThisIf) {
			return;
		}
		region.add(start);
	}

	private BlockNode processSwitch(IRegion currentRegion, BlockNode block, SwitchNode insn, RegionStack stack) {
		SwitchRegion sw = new SwitchRegion(currentRegion, block);
		currentRegion.getSubBlocks().add(sw);

		int len = insn.getTargets().length;
		// sort by target
		Map<BlockNode, List<Object>> blocksMap = new LinkedHashMap<>(len);
		for (int i = 0; i < len; i++) {
			Object key = insn.getKeys()[i];
			BlockNode targ = insn.getTargetBlocks()[i];
			List<Object> keys = blocksMap.computeIfAbsent(targ, k -> new ArrayList<>(2));
			keys.add(key);
		}
		BlockNode defCase = insn.getDefTargetBlock();
		if (defCase != null) {
			blocksMap.remove(defCase);
		}
		LoopInfo loop = mth.getLoopForBlock(block);

		Map<BlockNode, BlockNode> fallThroughCases = new LinkedHashMap<>();

		List<BlockNode> basicBlocks = mth.getBasicBlocks();
		BitSet outs = new BitSet(basicBlocks.size());
		outs.or(block.getDomFrontier());
		for (BlockNode s : block.getCleanSuccessors()) {
			BitSet df = s.getDomFrontier();
			// fall through case block
			if (df.cardinality() > 1) {
				if (df.cardinality() > 2) {
					LOG.debug("Unexpected case pattern, block: {}, mth: {}", s, mth);
				} else {
					BlockNode first = basicBlocks.get(df.nextSetBit(0));
					BlockNode second = basicBlocks.get(df.nextSetBit(first.getId() + 1));
					if (second.getDomFrontier().get(first.getId())) {
						fallThroughCases.put(s, second);
						df = new BitSet(df.size());
						df.set(first.getId());
					} else if (first.getDomFrontier().get(second.getId())) {
						fallThroughCases.put(s, first);
						df = new BitSet(df.size());
						df.set(second.getId());
					}
				}
			}
			outs.or(df);
		}
		outs.clear(block.getId());
		if (loop != null) {
			outs.clear(loop.getStart().getId());
		}

		stack.push(sw);
		stack.addExits(BlockUtils.bitSetToBlocks(mth, outs));

		// check cases order if fall through case exists
		if (!fallThroughCases.isEmpty()
				&& isBadCasesOrder(blocksMap, fallThroughCases)) {
			LOG.debug("Fixing incorrect switch cases order, method: {}", mth);
			blocksMap = reOrderSwitchCases(blocksMap, fallThroughCases);
			if (isBadCasesOrder(blocksMap, fallThroughCases)) {
				LOG.error("Can't fix incorrect switch cases order, method: {}", mth);
				mth.add(AFlag.INCONSISTENT_CODE);
			}
		}

		// filter 'out' block
		if (outs.cardinality() > 1) {
			// remove exception handlers
			BlockUtils.cleanBitSet(mth, outs);
		}
		if (outs.cardinality() > 1) {
			// filter loop start and successors of other blocks
			for (int i = outs.nextSetBit(0); i >= 0; i = outs.nextSetBit(i + 1)) {
				BlockNode b = basicBlocks.get(i);
				outs.andNot(b.getDomFrontier());
				if (b.contains(AFlag.LOOP_START)) {
					outs.clear(b.getId());
				} else {
					for (BlockNode s : b.getCleanSuccessors()) {
						outs.clear(s.getId());
					}
				}
			}
		}

		if (loop != null && outs.cardinality() > 1) {
			outs.clear(loop.getEnd().getId());
		}
		if (outs.cardinality() == 0) {
			// one or several case blocks are empty,
			// run expensive algorithm for find 'out' block
			for (BlockNode maybeOut : block.getSuccessors()) {
				boolean allReached = true;
				for (BlockNode s : block.getSuccessors()) {
					if (!isPathExists(s, maybeOut)) {
						allReached = false;
						break;
					}
				}
				if (allReached) {
					outs.set(maybeOut.getId());
					break;
				}
			}
		}
		BlockNode out = null;
		if (outs.cardinality() == 1) {
			out = basicBlocks.get(outs.nextSetBit(0));
			stack.addExit(out);
		} else if (loop == null && outs.cardinality() > 1) {
			LOG.warn("Can't detect out node for switch block: {} in {}", block, mth);
		}
		if (loop != null) {
			// check if 'continue' must be inserted
			BlockNode end = loop.getEnd();
			if (out != end && out != null) {
				insertContinueInSwitch(block, out, end);
			}
		}

		if (!stack.containsExit(defCase)) {
			Region defRegion = makeRegion(defCase, stack);
			if (RegionUtils.notEmpty(defRegion)) {
				sw.setDefaultCase(defRegion);
			}
		}
		for (Entry<BlockNode, List<Object>> entry : blocksMap.entrySet()) {
			BlockNode caseBlock = entry.getKey();
			if (stack.containsExit(caseBlock)) {
				// empty case block
				sw.addCase(entry.getValue(), new Region(stack.peekRegion()));
			} else {
				BlockNode next = fallThroughCases.get(caseBlock);
				stack.addExit(next);
				Region caseRegion = makeRegion(caseBlock, stack);
				stack.removeExit(next);
				if (next != null) {
					next.add(AFlag.FALL_THROUGH);
					caseRegion.add(AFlag.FALL_THROUGH);
				}
				sw.addCase(entry.getValue(), caseRegion);
				// 'break' instruction will be inserted in RegionMakerVisitor.PostRegionVisitor
			}
		}

		stack.pop();
		return out;
	}

	private boolean isBadCasesOrder(Map<BlockNode, List<Object>> blocksMap,
			Map<BlockNode, BlockNode> fallThroughCases) {
		BlockNode nextCaseBlock = null;
		for (BlockNode caseBlock : blocksMap.keySet()) {
			if (nextCaseBlock != null && !caseBlock.equals(nextCaseBlock)) {
				return true;
			}
			nextCaseBlock = fallThroughCases.get(caseBlock);
		}
		return nextCaseBlock != null;
	}

	private Map<BlockNode, List<Object>> reOrderSwitchCases(Map<BlockNode, List<Object>> blocksMap,
			Map<BlockNode, BlockNode> fallThroughCases) {
		List<BlockNode> list = new ArrayList<>(blocksMap.size());
		list.addAll(blocksMap.keySet());
		list.sort((a, b) -> {
			BlockNode nextA = fallThroughCases.get(a);
			if (nextA != null) {
				if (b.equals(nextA)) {
					return -1;
				}
			} else if (a.equals(fallThroughCases.get(b))) {
				return 1;
			}
			return 0;
		});

		Map<BlockNode, List<Object>> newBlocksMap = new LinkedHashMap<>(blocksMap.size());
		for (BlockNode key : list) {
			newBlocksMap.put(key, blocksMap.get(key));
		}
		return newBlocksMap;
	}

	private static void insertContinueInSwitch(BlockNode block, BlockNode out, BlockNode end) {
		int endId = end.getId();
		for (BlockNode s : block.getCleanSuccessors()) {
			if (s.getDomFrontier().get(endId) && s != out) {
				// search predecessor of loop end on path from this successor
				List<BlockNode> list = BlockUtils.collectBlocksDominatedBy(s, s);
				for (BlockNode p : end.getPredecessors()) {
					if (list.contains(p)) {
						if (p.isSynthetic()) {
							p.getInstructions().add(new InsnNode(InsnType.CONTINUE, 0));
						}
						break;
					}
				}
			}
		}
	}

	public IRegion processTryCatchBlocks(MethodNode mth) {
		Set<TryCatchBlock> tcs = new HashSet<>();
		for (ExceptionHandler handler : mth.getExceptionHandlers()) {
			tcs.add(handler.getTryBlock());
		}
		for (TryCatchBlock tc : tcs) {
			List<BlockNode> blocks = new ArrayList<>(tc.getHandlersCount());
			Set<BlockNode> splitters = new HashSet<>();
			for (ExceptionHandler handler : tc.getHandlers()) {
				BlockNode handlerBlock = handler.getHandlerBlock();
				if (handlerBlock != null) {
					blocks.add(handlerBlock);
					splitters.addAll(handlerBlock.getPredecessors());
				} else {
					LOG.debug(ErrorsCounter.formatMsg(mth, "No exception handler block: " + handler));
				}
			}
			Set<BlockNode> exits = new HashSet<>();
			for (BlockNode splitter : splitters) {
				for (BlockNode handler : blocks) {
					if (handler.contains(AFlag.REMOVE)) {
						continue;
					}
					List<BlockNode> s = splitter.getSuccessors();
					if (s.isEmpty()) {
						LOG.debug(ErrorsCounter.formatMsg(mth, "No successors for splitter: " + splitter));
						continue;
					}
					BlockNode ss = s.get(0);
					BlockNode cross = BlockUtils.getPathCross(mth, ss, handler);
					if (cross != null && cross != ss && cross != handler) {
						exits.add(cross);
					}
				}
			}
			for (ExceptionHandler handler : tc.getHandlers()) {
				processExcHandler(mth, handler, exits);
			}
		}
		return processHandlersOutBlocks(mth, tcs);
	}

	/**
	 * Search handlers successor blocks not included in any region.
	 */
	protected IRegion processHandlersOutBlocks(MethodNode mth, Set<TryCatchBlock> tcs) {
		Set<IBlock> allRegionBlocks = new HashSet<>();
		RegionUtils.getAllRegionBlocks(mth.getRegion(), allRegionBlocks);

		Set<IBlock> succBlocks = new HashSet<>();
		for (TryCatchBlock tc : tcs) {
			for (ExceptionHandler handler : tc.getHandlers()) {
				IContainer region = handler.getHandlerRegion();
				if (region != null) {
					IBlock lastBlock = RegionUtils.getLastBlock(region);
					if (lastBlock instanceof BlockNode) {
						succBlocks.addAll(((BlockNode) lastBlock).getSuccessors());
					}
					RegionUtils.getAllRegionBlocks(region, allRegionBlocks);
				}
			}
		}
		succBlocks.removeAll(allRegionBlocks);
		if (succBlocks.isEmpty()) {
			return null;
		}
		Region excOutRegion = new Region(mth.getRegion());
		for (IBlock block : succBlocks) {
			if (block instanceof BlockNode) {
				excOutRegion.add(makeRegion((BlockNode) block, new RegionStack(mth)));
			}
		}
		return excOutRegion;
	}

	private void processExcHandler(MethodNode mth, ExceptionHandler handler, Set<BlockNode> exits) {
		BlockNode start = handler.getHandlerBlock();
		if (start == null) {
			return;
		}
		RegionStack stack = new RegionStack(this.mth);
		BlockNode dom;
		if (handler.isFinally()) {
			SplitterBlockAttr splitterAttr = start.get(AType.SPLITTER_BLOCK);
			if (splitterAttr == null) {
				return;
			}
			dom = splitterAttr.getBlock();
		} else {
			dom = start;
			stack.addExits(exits);
		}
		if (dom.contains(AFlag.REMOVE)) {
			return;
		}
		BitSet domFrontier = dom.getDomFrontier();
		List<BlockNode> handlerExits = BlockUtils.bitSetToBlocks(this.mth, domFrontier);
		boolean inLoop = this.mth.getLoopForBlock(start) != null;
		for (BlockNode exit : handlerExits) {
			if ((!inLoop || BlockUtils.isPathExists(start, exit))
					&& RegionUtils.isRegionContainsBlock(this.mth.getRegion(), exit)) {
				stack.addExit(exit);
			}
		}
		handler.setHandlerRegion(makeRegion(start, stack));

		ExcHandlerAttr excHandlerAttr = start.get(AType.EXC_HANDLER);
		if (excHandlerAttr == null) {
			mth.addWarn("Missing exception handler attribute for start block: " + start);
		} else {
			handler.getHandlerRegion().addAttr(excHandlerAttr);
		}
	}

	static boolean isEqualPaths(BlockNode b1, BlockNode b2) {
		if (b1 == b2) {
			return true;
		}
		if (b1 == null || b2 == null) {
			return false;
		}
		return isEqualReturnBlocks(b1, b2) || isSyntheticPath(b1, b2);
	}

	private static boolean isSyntheticPath(BlockNode b1, BlockNode b2) {
		BlockNode n1 = skipSyntheticSuccessor(b1);
		BlockNode n2 = skipSyntheticSuccessor(b2);
		return (n1 != b1 || n2 != b2) && isEqualPaths(n1, n2);
	}

	public static boolean isEqualReturnBlocks(BlockNode b1, BlockNode b2) {
		if (!b1.isReturnBlock() || !b2.isReturnBlock()) {
			return false;
		}
		List<InsnNode> b1Insns = b1.getInstructions();
		List<InsnNode> b2Insns = b2.getInstructions();
		if (b1Insns.size() != 1 || b2Insns.size() != 1) {
			return false;
		}
		InsnNode i1 = b1Insns.get(0);
		InsnNode i2 = b2Insns.get(0);
		if (i1.getArgsCount() != i2.getArgsCount()) {
			return false;
		}
		return i1.getArgsCount() == 0 || i1.getArg(0).equals(i2.getArg(0));
	}
}
