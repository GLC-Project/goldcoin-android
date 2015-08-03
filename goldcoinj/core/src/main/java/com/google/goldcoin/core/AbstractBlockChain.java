/*
 * Copyright 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.goldcoin.core;

import com.google.common.base.Preconditions;
import com.google.goldcoin.store.BlockStore;
import com.google.goldcoin.store.BlockStoreException;
import com.google.goldcoin.utils.Locks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.lang.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.*;

/**
 * <p>An AbstractBlockChain holds a series of {@link Block} objects, links them together, and knows how to verify that
 * the chain follows the rules of the {@link NetworkParameters} for this chain.</p>
 * <p/>
 * <p>It can be connected to a {@link Wallet}, and also {@link BlockChainListener}s that can receive transactions and
 * notifications of re-organizations.</p>
 * <p/>
 * <p>An AbstractBlockChain implementation must be connected to a {@link BlockStore} implementation. The chain object
 * by itself doesn't store any data, that's delegated to the store. Which store you use is a decision best made by
 * reading the getting started guide, but briefly, fully validating block chains need fully validating stores. In
 * the lightweight SPV mode, a {@link com.google.goldcoin.store.BoundedOverheadBlockStore} may be a good choice.</p>
 * <p/>
 * <p>This class implements an abstract class which makes it simple to create a BlockChain that does/doesn't do full
 * verification.  It verifies headers and is implements most of what is required to implement SPV mode, but
 * also provides callback hooks which can be used to do full verification.</p>
 * <p/>
 * <p>There are two subclasses of AbstractBlockChain that are useful: {@link BlockChain}, which is the simplest
 * class and implements <i>simplified payment verification</i>. This is a lightweight and efficient mode that does
 * not verify the contents of blocks, just their headers. A {@link FullPrunedBlockChain} paired with a
 * {@link com.google.goldcoin.store.H2FullPrunedBlockStore} implements full verification, which is equivalent to the
 * original Satoshi client. To learn more about the alternative security models, please consult the articles on the
 * website.</p>
 * <p/>
 * <b>Theory</b>
 * <p/>
 * <p>The 'chain' is actually a tree although in normal operation it operates mostly as a list of {@link Block}s.
 * When multiple new head blocks are found simultaneously, there are multiple stories of the economy competing to become
 * the one true consensus. This can happen naturally when two miners solve a block within a few seconds of each other,
 * or it can happen when the chain is under attack.</p>
 * <p/>
 * <p>A reference to the head block of the best known chain is stored. If you can reach the genesis block by repeatedly
 * walking through the prevBlock pointers, then we say this is a full chain. If you cannot reach the genesis block
 * we say it is an orphan chain. Orphan chains can occur when blocks are solved and received during the initial block
 * chain download, or if we connect to a peer that doesn't send us blocks in order.</p>
 * <p/>
 * <p>A reorganize occurs when the blocks that make up the best known chain changes. Note that simply adding a
 * new block to the top of the best chain isn't as reorganize, but that a reorganize is always triggered by adding
 * a new block that connects to some other (non best head) block. By "best" we mean the chain representing the largest
 * amount of work done.</p>
 * <p/>
 * <p>Every so often the block chain passes a difficulty transition point. At that time, all the blocks in the last
 * 2016 blocks are examined and a new difficulty target is calculated from them.</p>
 */
public abstract class AbstractBlockChain {
    private static final Logger log = LoggerFactory.getLogger(AbstractBlockChain.class);
    protected ReentrantLock lock = Locks.lock("blockchain");

    /**
     * Keeps a map of block hashes to StoredBlocks.
     */
    private final BlockStore blockStore;

    /**
     * Tracks the top of the best known chain.<p>
     * <p/>
     * Following this one down to the genesis block produces the story of the economy from the creation of goldcoin
     * until the present day. The chain head can change if a new set of blocks is received that results in a chain of
     * greater work than the one obtained by following this one down. In that case a reorganize is triggered,
     * potentially invalidating transactions in our wallet.
     */
    protected StoredBlock chainHead;

    // TODO: Scrap this and use a proper read/write for all of the block chain objects.
    // The chainHead field is read/written synchronized with this object rather than BlockChain. However writing is
    // also guaranteed to happen whilst BlockChain is synchronized (see setChainHead). The goal of this is to let
    // clients quickly access the chain head even whilst the block chain is downloading and thus the BlockChain is
    // locked most of the time.
    private final Object chainHeadLock = new Object();

    protected final NetworkParameters params;
    private final CopyOnWriteArrayList<BlockChainListener> listeners;

    // Holds a block header and, optionally, a list of tx hashes or block's transactions
    protected static class OrphanBlock {
        Block block;
        Set<Sha256Hash> filteredTxHashes;
        List<Transaction> filteredTxn;

        OrphanBlock(Block block, Set<Sha256Hash> filteredTxHashes, List<Transaction> filteredTxn) {
            Preconditions.checkArgument((block.transactions == null && filteredTxHashes != null && filteredTxn != null)
                    || (block.transactions != null && filteredTxHashes == null && filteredTxn == null));
            this.block = block;
            this.filteredTxHashes = filteredTxHashes;
            this.filteredTxn = filteredTxn;
        }
    }

    // Holds blocks that we have received but can't plug into the chain yet, eg because they were created whilst we
    // were downloading the block chain.
    private final LinkedHashMap<Sha256Hash, OrphanBlock> orphanBlocks = new LinkedHashMap<Sha256Hash, OrphanBlock>();

    /**
     * Constructs a BlockChain connected to the given list of listeners (eg, wallets) and a store.
     */
    public AbstractBlockChain(NetworkParameters params, List<BlockChainListener> listeners,
                              BlockStore blockStore) throws BlockStoreException {
        this.blockStore = blockStore;
        chainHead = blockStore.getChainHead();
        log.info("chain head is at height {}:\n{}", chainHead.getHeight(), chainHead.getHeader());
        this.params = params;
        this.listeners = new CopyOnWriteArrayList<BlockChainListener>(listeners);
    }

    /**
     * Add a wallet to the BlockChain. Note that the wallet will be unaffected by any blocks received while it
     * was not part of this BlockChain. This method is useful if the wallet has just been created, and its keys
     * have never been in use, or if the wallet has been loaded along with the BlockChain. Note that adding multiple
     * wallets is not well tested!
     */
    public void addWallet(Wallet wallet) {
        listeners.add(wallet);
    }

    /**
     * Adds a generic {@link BlockChainListener} listener to the chain.
     */
    public void addListener(BlockChainListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes the given {@link BlockChainListener} from the chain.
     */
    public void removeListener(BlockChainListener listener) {
        listeners.remove(listener);
    }

    /**
     * Returns the {@link BlockStore} the chain was constructed with. You can use this to iterate over the chain.
     */
    public BlockStore getBlockStore() {
        return blockStore;
    }

    /**
     * Adds/updates the given {@link Block} with the block store.
     * This version is used when the transactions have not been verified.
     *
     * @param storedPrev The {@link StoredBlock} which immediately precedes block.
     * @param block      The {@link Block} to add/update.
     * @returns the newly created {@link StoredBlock}
     */
    protected abstract StoredBlock addToBlockStore(StoredBlock storedPrev, Block block)
            throws BlockStoreException, VerificationException;

    /**
     * Adds/updates the given {@link StoredBlock} with the block store.
     * This version is used when the transactions have already been verified to properly spend txOutputChanges.
     *
     * @param storedPrev      The {@link StoredBlock} which immediately precedes block.
     * @param header          The {@link StoredBlock} to add/update.
     * @param txOutputChanges The total sum of all changes made by this block to the set of open transaction outputs (from a call to connectTransactions)
     * @returns the newly created {@link StoredBlock}
     */
    protected abstract StoredBlock addToBlockStore(StoredBlock storedPrev, Block header,
                                                   TransactionOutputChanges txOutputChanges)
            throws BlockStoreException, VerificationException;

    /**
     * Called before setting chain head in memory.
     * Should write the new head to block store and then commit any database transactions
     * that were started by disconnectTransactions/connectTransactions.
     */
    protected abstract void doSetChainHead(StoredBlock chainHead) throws BlockStoreException;

    /**
     * Called if we (possibly) previously called disconnectTransaction/connectTransactions,
     * but will not be calling preSetChainHead as a block failed verification.
     * Can be used to abort database transactions that were started by
     * disconnectTransactions/connectTransactions.
     */
    protected abstract void notSettingChainHead() throws BlockStoreException;

    /**
     * For a standard BlockChain, this should return blockStore.get(hash),
     * for a FullPrunedBlockChain blockStore.getOnceUndoableStoredBlock(hash)
     */
    protected abstract StoredBlock getStoredBlockInCurrentScope(Sha256Hash hash) throws BlockStoreException;

    /**
     * Processes a received block and tries to add it to the chain. If there's something wrong with the block an
     * exception is thrown. If the block is OK but cannot be connected to the chain at this time, returns false.
     * If the block can be connected to the chain, returns true.
     */
    public boolean add(Block block) throws VerificationException, PrunedException {
        try {
            return add(block, null, null, true);
        } catch (BlockStoreException e) {
            // TODO: Figure out a better way to propagate this exception to the user.
            throw new RuntimeException(e);
        } catch (VerificationException e) {
            try {
                notSettingChainHead();
            } catch (BlockStoreException e1) {
                throw new RuntimeException(e1);
            }
            throw new VerificationException("Could not verify block " + block.getHashAsString() + "\n" +
                    block.toString(), e);
        }
    }

    /**
     * Processes a received block and tries to add it to the chain. If there's something wrong with the block an
     * exception is thrown. If the block is OK but cannot be connected to the chain at this time, returns false.
     * If the block can be connected to the chain, returns true.
     */
    public boolean add(FilteredBlock block) throws VerificationException, PrunedException {
        try {
            // The block has a list of hashes of transactions that matched the Bloom filter, and a list of associated
            // Transaction objects. There may be fewer Transaction objects than hashes, this is expected. It can happen
            // in the case where we were already around to witness the initial broadcast, so we downloaded the
            // transaction and sent it to the wallet before this point (the wallet may have thrown it away if it was
            // a false positive, as expected in any Bloom filtering scheme). The filteredTxn list here will usually
            // only be full of data when we are catching up to the head of the chain and thus haven't witnessed any
            // of the transactions.
            Set<Sha256Hash> filteredTxnHashSet = new HashSet<Sha256Hash>(block.getTransactionHashes());
            List<Transaction> filteredTxn = block.getAssociatedTransactions();
            for (Transaction tx : filteredTxn) {
                checkState(filteredTxnHashSet.remove(tx.getHash()));
            }
            return add(block.getBlockHeader(), filteredTxnHashSet, filteredTxn, true);
        } catch (BlockStoreException e) {
            // TODO: Figure out a better way to propagate this exception to the user.
            throw new RuntimeException(e);
        } catch (VerificationException e) {
            try {
                notSettingChainHead();
            } catch (BlockStoreException e1) {
                throw new RuntimeException(e1);
            }
            throw new VerificationException("Could not verify block " + block.getHash().toString() + "\n" +
                    block.toString(), e);
        }
    }

    /**
     * Whether or not we are maintaining a set of unspent outputs and are verifying all transactions.
     * Also indicates that all calls to add() should provide a block containing transactions
     */
    protected abstract boolean shouldVerifyTransactions();

    /**
     * Connect each transaction in block.transactions, verifying them as we go and removing spent outputs
     * If an error is encountered in a transaction, no changes should be made to the underlying BlockStore.
     * and a VerificationException should be thrown.
     * Only called if(shouldVerifyTransactions())
     *
     * @return The full set of all changes made to the set of open transaction outputs.
     * @throws VerificationException if an attempt was made to spend an already-spent output, or if a transaction incorrectly solved an output script.
     * @throws BlockStoreException   if the block store had an underlying error.
     */
    protected abstract TransactionOutputChanges connectTransactions(int height, Block block) throws VerificationException, BlockStoreException;

    /**
     * Load newBlock from BlockStore and connect its transactions, returning changes to the set of unspent transactions.
     * If an error is encountered in a transaction, no changes should be made to the underlying BlockStore.
     * Only called if(shouldVerifyTransactions())
     *
     * @return The full set of all changes made to the set of open transaction outputs.
     * @throws PrunedException       if newBlock does not exist as a {@link StoredUndoableBlock} in the block store.
     * @throws VerificationException if an attempt was made to spend an already-spent output, or if a transaction incorrectly solved an output script.
     * @throws BlockStoreException   if the block store had an underlying error or newBlock does not exist in the block store at all.
     */
    protected abstract TransactionOutputChanges connectTransactions(StoredBlock newBlock) throws VerificationException, BlockStoreException, PrunedException;

    // Stat counters.
    private long statsLastTime = System.currentTimeMillis();
    private long statsBlocksAdded;

    // filteredTxHashList and filteredTxn[i].GetHash() should be mutually exclusive
    private boolean add(Block block, Set<Sha256Hash> filteredTxHashList, List<Transaction> filteredTxn, boolean tryConnecting)
            throws BlockStoreException, VerificationException, PrunedException {
        lock.lock();
        try {
            // TODO: Use read/write locks to ensure that during chain download properties are still low latency.
            if (System.currentTimeMillis() - statsLastTime > 1000) {
                // More than a second passed since last stats logging.
                if (statsBlocksAdded > 1)
                    log.info("{} blocks per second", statsBlocksAdded);
                statsLastTime = System.currentTimeMillis();
                statsBlocksAdded = 0;
            }
            // Quick check for duplicates to avoid an expensive check further down (in findSplit). This can happen a lot
            // when connecting orphan transactions due to the dumb brute force algorithm we use.
            if (block.equals(getChainHead().getHeader())) {
                return true;
            }
            if (tryConnecting && orphanBlocks.containsKey(block.getHash())) {
                return false;
            }

            // If we want to verify transactions (ie we are running with full blocks), verify that block has transactions
            if (shouldVerifyTransactions() && block.transactions == null)
                throw new VerificationException("Got a block header while running in full-block mode");

            // Does this block contain any transactions we might care about? Check this up front before verifying the
            // blocks validity so we can skip the merkle root verification if the contents aren't interesting. This saves
            // a lot of time for big blocks.
            boolean contentsImportant = shouldVerifyTransactions();
            if (block.transactions != null) {
                contentsImportant = contentsImportant || containsRelevantTransactions(block);
            }

            // Prove the block is internally valid: hash is lower than target, etc. This only checks the block contents
            // if there is a tx sending or receiving coins using an address in one of our wallets. And those transactions
            // are only lightly verified: presence in a valid connecting block is taken as proof of validity. See the
            // article here for more details: http://code.google.com/p/litecoinj/wiki/SecurityModel
            try {
                block.verifyHeader();
                if (contentsImportant)
                    block.verifyTransactions();
            } catch (VerificationException e) {
                log.error("Failed to verify block: ", e);
                log.error(block.getHashAsString());
                throw e;
            }

            // Try linking it to a place in the currently known blocks.
            StoredBlock storedPrev = getStoredBlockInCurrentScope(block.getPrevBlockHash());

            if (storedPrev == null) {
                // We can't find the previous block. Probably we are still in the process of downloading the chain and a
                // block was solved whilst we were doing it. We put it to one side and try to connect it later when we
                // have more blocks.
                checkState(tryConnecting, "bug in tryConnectingOrphans");
                log.warn("Block does not connect: {} prev {}", block.getHashAsString(), block.getPrevBlockHash());
                orphanBlocks.put(block.getHash(), new OrphanBlock(block, filteredTxHashList, filteredTxn));
                return false;
            } else {
                // It connects to somewhere on the chain. Not necessarily the top of the best known chain.
                //
                // Create a new StoredBlock from this block. It will throw away the transaction data so when block goes
                // out of scope we will reclaim the used memory.
                checkDifficultyTransitions(storedPrev, block);
                connectBlock(block, storedPrev, shouldVerifyTransactions(), filteredTxHashList, filteredTxn);
            }

            if (tryConnecting)
                tryConnectingOrphans();

            statsBlocksAdded++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    // expensiveChecks enables checks that require looking at blocks further back in the chain
    // than the previous one when connecting (eg median timestamp check)
    // It could be exposed, but for now we just set it to shouldVerifyTransactions()
    private void connectBlock(Block block, StoredBlock storedPrev, boolean expensiveChecks,
                              Set<Sha256Hash> filteredTxHashList, List<Transaction> filteredTxn)
            throws BlockStoreException, VerificationException, PrunedException {
        checkState(lock.isLocked());
        // Check that we aren't connecting a block that fails a checkpoint check
        if (!params.passesCheckpoint(storedPrev.getHeight() + 1, block.getHash()))
            throw new VerificationException("Block failed checkpoint lockin at " + (storedPrev.getHeight() + 1));
        if (shouldVerifyTransactions())
            for (Transaction tx : block.transactions)
                if (!tx.isFinal(storedPrev.getHeight() + 1, block.getTimeSeconds()))
                    throw new VerificationException("Block contains non-final transaction");

        StoredBlock head = getChainHead();
        if (storedPrev.equals(head)) {
            if (expensiveChecks && block.getTimeSeconds() <= getMedianTimestampOfRecentBlocks(head, blockStore))
                throw new VerificationException("Block's timestamp is too early " + block.getTimeSeconds() + " : " + getMedianTimestampOfRecentBlocks(head, blockStore));

            // This block connects to the best known block, it is a normal continuation of the system.
            TransactionOutputChanges txOutChanges = null;
            if (shouldVerifyTransactions())
                txOutChanges = connectTransactions(storedPrev.getHeight() + 1, block);
            StoredBlock newStoredBlock = addToBlockStore(storedPrev,
                    block.transactions == null ? block : block.cloneAsHeader(), txOutChanges);
            setChainHead(newStoredBlock);
            log.debug("Chain is now {} blocks high", newStoredBlock.getHeight());
            // Notify the listeners of the new block, so the depth and workDone of stored transactions can be updated
            // (in the case of the listener being a wallet). Wallets need to know how deep each transaction is so
            // coinbases aren't used before maturity.
            boolean first = true;
            for (BlockChainListener listener : listeners) {
                if (block.transactions != null || filteredTxn != null) {
                    // If this is not the first wallet, ask for the transactions to be duplicated before being given
                    // to the wallet when relevant. This ensures that if we have two connected wallets and a tx that
                    // is relevant to both of them, they don't end up accidentally sharing the same object (which can
                    // result in temporary in-memory corruption during re-orgs). See bug 257. We only duplicate in
                    // the case of multiple wallets to avoid an unnecessary efficiency hit in the common case.
                    sendTransactionsToListener(newStoredBlock, NewBlockType.BEST_CHAIN, listener,
                            block.transactions != null ? block.transactions : filteredTxn, !first);
                }
                if (filteredTxHashList != null) {
                    for (Sha256Hash hash : filteredTxHashList) {
                        listener.notifyTransactionIsInBlock(hash, newStoredBlock, NewBlockType.BEST_CHAIN);
                    }
                }
                listener.notifyNewBestBlock(newStoredBlock);
                first = false;
            }
        } else {
            // This block connects to somewhere other than the top of the best known chain. We treat these differently.
            //
            // Note that we send the transactions to the wallet FIRST, even if we're about to re-organize this block
            // to become the new best chain head. This simplifies handling of the re-org in the Wallet class.
            StoredBlock newBlock = storedPrev.build(block);
            boolean haveNewBestChain = newBlock.moreWorkThan(head);
            if (haveNewBestChain) {
                log.info("Block is causing a re-organize");
            } else {
                StoredBlock splitPoint = findSplit(newBlock, head, blockStore);
                if (splitPoint != null && splitPoint.equals(newBlock)) {
                    // newStoredBlock is a part of the same chain, there's no fork. This happens when we receive a block
                    // that we already saw and linked into the chain previously, which isn't the chain head.
                    // Re-processing it is confusing for the wallet so just skip.
                    log.warn("Saw duplicated block in main chain at height {}: {}",
                            newBlock.getHeight(), newBlock.getHeader().getHash());
                    return;
                }
                if (splitPoint == null) {
                    // This should absolutely never happen
                    // (lets not write the full block to disk to keep any bugs which allow this to happen
                    //  from writing unreasonable amounts of data to disk)
                    throw new VerificationException("Block forks the chain but splitPoint is null");
                } else {
                    // We aren't actually spending any transactions (yet) because we are on a fork
                    addToBlockStore(storedPrev, block);
                    int splitPointHeight = splitPoint.getHeight();
                    String splitPointHash = splitPoint.getHeader().getHashAsString();
                    log.info("Block forks the chain at height {}/block {}, but it did not cause a reorganize:\n{}",
                            new Object[]{splitPointHeight, splitPointHash, newBlock.getHeader().getHashAsString()});
                }
            }

            // We may not have any transactions if we received only a header, which can happen during fast catchup.
            // If we do, send them to the wallet but state that they are on a side chain so it knows not to try and
            // spend them until they become activated.
            if (block.transactions != null || filteredTxn != null) {
                boolean first = true;
                for (BlockChainListener listener : listeners) {
                    List<Transaction> txnToNotify;
                    if (block.transactions != null)
                        txnToNotify = block.transactions;
                    else
                        txnToNotify = filteredTxn;
                    // If this is not the first wallet, ask for the transactions to be duplicated before being given
                    // to the wallet when relevant. This ensures that if we have two connected wallets and a tx that
                    // is relevant to both of them, they don't end up accidentally sharing the same object (which can
                    // result in temporary in-memory corruption during re-orgs). See bug 257. We only duplicate in
                    // the case of multiple wallets to avoid an unnecessary efficiency hit in the common case.
                    sendTransactionsToListener(newBlock, NewBlockType.SIDE_CHAIN, listener, txnToNotify, first);
                    if (filteredTxHashList != null) {
                        for (Sha256Hash hash : filteredTxHashList) {
                            listener.notifyTransactionIsInBlock(hash, newBlock, NewBlockType.SIDE_CHAIN);
                        }
                    }
                    first = false;
                }
            }

            if (haveNewBestChain)
                handleNewBestChain(storedPrev, newBlock, block, expensiveChecks);
        }
    }

    /**
     * Gets the median timestamp of the last 11 blocks
     */
    private static long getMedianTimestampOfRecentBlocks(StoredBlock storedBlock,
                                                         BlockStore store) throws BlockStoreException {
        long[] timestamps = new long[11];
        int unused = 9;
        timestamps[10] = storedBlock.getHeader().getTimeSeconds();
        while (unused >= 0 && (storedBlock = storedBlock.getPrev(store)) != null)
            timestamps[unused--] = storedBlock.getHeader().getTimeSeconds();

        Arrays.sort(timestamps, unused + 1, 11);
        return timestamps[unused + (11 - unused) / 2];
    }

    /**
     * Disconnect each transaction in the block (after reading it from the block store)
     * Only called if(shouldVerifyTransactions())
     *
     * @throws PrunedException     if block does not exist as a {@link StoredUndoableBlock} in the block store.
     * @throws BlockStoreException if the block store had an underlying error or block does not exist in the block store at all.
     */
    protected abstract void disconnectTransactions(StoredBlock block) throws PrunedException, BlockStoreException;

    /**
     * Called as part of connecting a block when the new block results in a different chain having higher total work.
     * <p/>
     * if (shouldVerifyTransactions)
     * Either newChainHead needs to be in the block store as a FullStoredBlock, or (block != null && block.transactions != null)
     */
    private void handleNewBestChain(StoredBlock storedPrev, StoredBlock newChainHead, Block block, boolean expensiveChecks)
            throws BlockStoreException, VerificationException, PrunedException {
        checkState(lock.isLocked());
        // This chain has overtaken the one we currently believe is best. Reorganize is required.
        //
        // Firstly, calculate the block at which the chain diverged. We only need to examine the
        // chain from beyond this block to find differences.
        StoredBlock head = getChainHead();
        StoredBlock splitPoint = findSplit(newChainHead, head, blockStore);
        log.info("Re-organize after split at height {}", splitPoint.getHeight());
        log.info("Old chain head: {}", head.getHeader().getHashAsString());
        log.info("New chain head: {}", newChainHead.getHeader().getHashAsString());
        log.info("Split at block: {}", splitPoint.getHeader().getHashAsString());
        // Then build a list of all blocks in the old part of the chain and the new part.
        LinkedList<StoredBlock> oldBlocks = getPartialChain(head, splitPoint, blockStore);
        LinkedList<StoredBlock> newBlocks = getPartialChain(newChainHead, splitPoint, blockStore);
        // Disconnect each transaction in the previous main chain that is no longer in the new main chain
        StoredBlock storedNewHead = splitPoint;
        if (shouldVerifyTransactions()) {
            for (StoredBlock oldBlock : oldBlocks) {
                try {
                    disconnectTransactions(oldBlock);
                } catch (PrunedException e) {
                    // We threw away the data we need to re-org this deep! We need to go back to a peer with full
                    // block contents and ask them for the relevant data then rebuild the indexs. Or we could just
                    // give up and ask the human operator to help get us unstuck (eg, rescan from the genesis block).
                    // TODO: Retry adding this block when we get a block with hash e.getHash()
                    throw e;
                }
            }
            StoredBlock cursor;
            // Walk in ascending chronological order.
            for (Iterator<StoredBlock> it = newBlocks.descendingIterator(); it.hasNext(); ) {
                cursor = it.next();
                if (expensiveChecks && cursor.getHeader().getTimeSeconds() <= getMedianTimestampOfRecentBlocks(cursor.getPrev(blockStore), blockStore))
                    throw new VerificationException("Block's timestamp is too early during reorg");
                TransactionOutputChanges txOutChanges;
                if (cursor != newChainHead || block == null)
                    txOutChanges = connectTransactions(cursor);
                else
                    txOutChanges = connectTransactions(newChainHead.getHeight(), block);
                storedNewHead = addToBlockStore(storedNewHead, cursor.getHeader(), txOutChanges);
            }
        } else {
            // (Finally) write block to block store
            storedNewHead = addToBlockStore(storedPrev, newChainHead.getHeader());
        }
        // Now inform the listeners. This is necessary so the set of currently active transactions (that we can spend)
        // can be updated to take into account the re-organize. We might also have received new coins we didn't have
        // before and our previous spends might have been undone.
        for (int i = 0; i < listeners.size(); i++) {
            BlockChainListener listener = listeners.get(i);
            listener.reorganize(splitPoint, oldBlocks, newBlocks);
            if (i == listeners.size()) {
                break;  // Listener removed itself and it was the last one.
            } else if (listeners.get(i) != listener) {
                i--;  // Listener removed itself and it was not the last one.
            }
        }
        // Update the pointer to the best known block.
        setChainHead(storedNewHead);
    }

    /**
     * Returns the set of contiguous blocks between 'higher' and 'lower'. Higher is included, lower is not.
     */
    private static LinkedList<StoredBlock> getPartialChain(StoredBlock higher, StoredBlock lower, BlockStore store) throws BlockStoreException {
        checkArgument(higher.getHeight() > lower.getHeight(), "higher and lower are reversed");
        LinkedList<StoredBlock> results = new LinkedList<StoredBlock>();
        StoredBlock cursor = higher;
        while (true) {
            results.add(cursor);
            cursor = checkNotNull(cursor.getPrev(store), "Ran off the end of the chain");
            if (cursor.equals(lower)) break;
        }
        return results;
    }

    /**
     * Locates the point in the chain at which newStoredBlock and chainHead diverge. Returns null if no split point was
     * found (ie they are not part of the same chain). Returns newChainHead or chainHead if they don't actually diverge
     * but are part of the same chain.
     */
    private static StoredBlock findSplit(StoredBlock newChainHead, StoredBlock oldChainHead,
                                         BlockStore store) throws BlockStoreException {
        StoredBlock currentChainCursor = oldChainHead;
        StoredBlock newChainCursor = newChainHead;
        // Loop until we find the block both chains have in common. Example:
        //
        //    A -> B -> C -> D
        //         \--> E -> F -> G
        //
        // findSplit will return block B. oldChainHead = D and newChainHead = G.
        while (!currentChainCursor.equals(newChainCursor)) {
            if (currentChainCursor.getHeight() > newChainCursor.getHeight()) {
                currentChainCursor = currentChainCursor.getPrev(store);
                checkNotNull(currentChainCursor, "Attempt to follow an orphan chain");
            } else {
                newChainCursor = newChainCursor.getPrev(store);
                checkNotNull(newChainCursor, "Attempt to follow an orphan chain");
            }
        }
        return currentChainCursor;
    }

    /**
     * @return the height of the best known chain, convenience for <tt>getChainHead().getHeight()</tt>.
     */
    public int getBestChainHeight() {
        return getChainHead().getHeight();
    }

    public enum NewBlockType {
        BEST_CHAIN,
        SIDE_CHAIN
    }

    private static void sendTransactionsToListener(StoredBlock block, NewBlockType blockType,
                                                   BlockChainListener listener,
                                                   List<Transaction> transactions,
                                                   boolean clone) throws VerificationException {
        for (Transaction tx : transactions) {
            try {
                if (listener.isTransactionRelevant(tx)) {
                    if (clone)
                        tx = new Transaction(tx.params, tx.litecoinSerialize());
                    listener.receiveFromBlock(tx, block, blockType);
                }
            } catch (ScriptException e) {
                // We don't want scripts we don't understand to break the block chain so just note that this tx was
                // not scanned here and continue.
                log.warn("Failed to parse a script: " + e.toString());
            } catch (ProtocolException e) {
                // Failed to duplicate tx, should never happen.
                throw new RuntimeException(e);
            }
        }
    }

    protected void setChainHead(StoredBlock chainHead) throws BlockStoreException {
        doSetChainHead(chainHead);
        synchronized (chainHeadLock) {
            this.chainHead = chainHead;
        }
    }

    /**
     * For each block in orphanBlocks, see if we can now fit it on top of the chain and if so, do so.
     */
    private void tryConnectingOrphans() throws VerificationException, BlockStoreException, PrunedException {
        checkState(lock.isLocked());
        // For each block in our orphan list, try and fit it onto the head of the chain. If we succeed remove it
        // from the list and keep going. If we changed the head of the list at the end of the round try again until
        // we can't fit anything else on the top.
        //
        // This algorithm is kind of crappy, we should do a topo-sort then just connect them in order, but for small
        // numbers of orphan blocks it does OK.
        int blocksConnectedThisRound;
        do {
            blocksConnectedThisRound = 0;
            Iterator<OrphanBlock> iter = orphanBlocks.values().iterator();
            while (iter.hasNext()) {
                OrphanBlock orphanBlock = iter.next();
                log.debug("Trying to connect {}", orphanBlock.block.getHash());
                // Look up the blocks previous.
                StoredBlock prev = getStoredBlockInCurrentScope(orphanBlock.block.getPrevBlockHash());
                if (prev == null) {
                    // This is still an unconnected/orphan block.
                    log.debug("  but it is not connectable right now");
                    continue;
                }
                // Otherwise we can connect it now.
                // False here ensures we don't recurse infinitely downwards when connecting huge chains.
                add(orphanBlock.block, orphanBlock.filteredTxHashes, orphanBlock.filteredTxn, false);
                iter.remove();
                blocksConnectedThisRound++;
            }
            if (blocksConnectedThisRound > 0) {
                log.info("Connected {} orphan blocks.", blocksConnectedThisRound);
            }
        } while (blocksConnectedThisRound > 0);
    }
    //TODO::change for GLDcoin?
    // February 16th 2012
    private static Date testnetDiffDate = new Date(1329264000000L);

    /*protected class comp64 implements Comparator<int> {
        @Override
        public int compare(int a, int b) {
            return a > b ? 1 : 0;
        }
    } */

    /**
     * Throws an exception if the blocks difficulty is not correct.
     */
    private void checkDifficultyTransitions1(StoredBlock storedPrev, Block nextBlock) throws BlockStoreException, VerificationException {
        checkState(lock.isLocked());
        Block prev = storedPrev.getHeader();

        // GoldCoin was forked two times.  Each time it had a new difficulty retarget (2016, 504, 60)
		// We need to get the right interval.
        int height = storedPrev.getHeight() + 1;
        int interval =  params.getInterval(height);

        //julyFork2 whether or not we had a massive difficulty fall authorized
        boolean didHalfAdjust = false;

        //if (height > 102999) throw new VerificationException("stop at 102,999");

        if (((height %  interval) != 0) && (height != GoldcoinDefinition.newDifficultyProtocolFork) /*&& (height != GoldcoinDefinition.novemberFork)*/) {

            // TODO: Refactor this hack after 0.5 is released and we stop supporting deserialization compatibility. This will not work for goldcoin
            // This should be a method of the NetworkParameters, which should in turn be using singletons and a subclass
            // for each network type. Then each network can define its own difficulty transition rules.
            if (params.getId().equals(NetworkParameters.ID_TESTNET) && nextBlock.getTime().after(testnetDiffDate)) {
                checkTestnetDifficulty(storedPrev, prev, nextBlock);
                return;
            }

            // No ... so check the difficulty didn't actually change.
            if (nextBlock.getDifficultyTarget() != prev.getDifficultyTarget())
                throw new VerificationException("Unexpected change in difficulty at height " + storedPrev.getHeight() +
                        ": " + Long.toHexString(nextBlock.getDifficultyTarget()) + " vs " +
                        Long.toHexString(prev.getDifficultyTarget()));
            return;
        }

        // We need to find a block far back in the chain. It's OK that this is expensive because it only occurs every
        // two weeks after the initial block chain download.
        long now = System.currentTimeMillis();
        StoredBlock cursor = blockStore.get(prev.getHash());

        int [] last59BlockSolvingTimes = new int [59];
        long [] last60BlockTimes = new long [60];
        long lastBlockTime = 0;
        long thisBlockTime = 0;

		//More changes for GoldCoin to handle the different intervals
		//This part will use the interval based on the block height retrieved above.
        int goBack = /*params.*/interval - 1;
        if (cursor.getHeight() + 1 != /*params.*/interval)
            goBack = /*params.*/interval;

        for (int i = 0; i < goBack; i++) {
            if (cursor == null) {
                // This should never happen. If it does, it means we are following an incorrect or busted chain.
                //we are loading from the checkpoints file
                return;
                //throw new VerificationException(
                //        "Difficulty transition point but we did not find a way back to the genesis block.");
            }



            if(GoldcoinDefinition.usingMedianDifficultyProtocol(height))
            {
                thisBlockTime = cursor.getHeader().getTimeSeconds();
                last60BlockTimes[i] = thisBlockTime;
                if(i > 0)
                {
                    last59BlockSolvingTimes[i-1] = (int)Math.abs(thisBlockTime - lastBlockTime);
                }
                lastBlockTime = thisBlockTime;
            }
            cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
        }
        long elapsed = System.currentTimeMillis() - now;
        if (elapsed > 50)
            log.info("Difficulty transition traversal took {}msec", elapsed);

        // Check if our cursor is null.  If it is, we've used checkpoints to restore.
        if (cursor == null) return;
        //TODO::This code is also not working for gldcoin, i don't think
        Block blockIntervalAgo = cursor.getHeader();
        long timespan = (int) (prev.getTimeSeconds() - blockIntervalAgo.getTimeSeconds());
        long medTime = 0;

        if(GoldcoinDefinition.usingMedianDifficultyProtocol(height)) {
            Arrays.sort(last59BlockSolvingTimes);
            medTime = last59BlockSolvingTimes[29];
        }


        if(height > GoldcoinDefinition.mayFork) {
            //Difficulty Fix here for case where average time between blocks becomes far longer than 2 minutes, even though median time is close to 2 minutes.
            //Uses the last 120 blocks(Should be 4 hours) for calculating

            log.info("May Fork mode \n");

            long [] last120BlockTimes = new long[120];
            System.arraycopy(last60BlockTimes, 0, last120BlockTimes, 0, 60);
            goBack = 120;
            for (int i = 60; i < goBack; i++) {
                if (cursor == null) {
                    // This should never happen. If it does, it means we are following an incorrect or busted chain.
                    //we are loading from the checkpoints file
                    return;
                    //throw new VerificationException(
                    //        "Difficulty transition point but we did not find a way back to the genesis block.");
                }
                last120BlockTimes[i] = cursor.getHeader().getTimeSeconds();
                cursor = blockStore.get(cursor.getHeader().getPrevBlockHash());
            }




            // Limit adjustment step
            //We need to set this in a way that reflects how fast blocks are actually being solved..
            //First we find the last 120 blocks and take the time between blocks
            //That gives us a list of 119 time differences
            //Then we take the average of those times and multiply it by 60 to get our actualtimespan

            int [] last119TimeDifferences = new int [119];
            //std::vector<int64> last119TimeDifferences;

            int xy = 0;
            System.arraycopy(last59BlockSolvingTimes, 0, last119TimeDifferences, 0, last59BlockSolvingTimes.length);
            for(xy = 59; xy < 119; ++xy)
            {

                last119TimeDifferences[xy] = (int)(Math.abs(last120BlockTimes[xy] - last120BlockTimes[xy+1]));
                //xy++;
            }

            long total = 0;
            for(int x = 0; x < 119; x++) {
                long timeN = last119TimeDifferences[x];
                //printf(" GetNextWorkRequired(): Current Time difference is: %"PRI64d" \n",timeN);
                total += timeN;
            }

            long averageTime = total/119;


            log.info("Average time between blocks over the last 120 blocks is: " +averageTime);
            /*printf(" GetNextWorkRequired(): Total Time (over 119 time differences) is: %"PRI64d" \n",total);
            printf(" GetNextWorkRequired(): First Time (over 119 time differences) is: %"PRI64d" \n",last119TimeDifferences[0]);
            printf(" GetNextWorkRequired(): Last Time (over 119 time differences) is: %"PRI64d" \n",last119TimeDifferences[118]);
            printf(" GetNextWorkRequired(): Last Time is: %"PRI64d" \n",last120BlockTimes[119]);
            printf(" GetNextWorkRequired(): 2nd Last Time is: %"PRI64d" \n",last120BlockTimes[118]);

            printf(" GetNextWorkRequired(): First Time is: %"PRI64d" \n",last120BlockTimes[0]);
            printf(" GetNextWorkRequired(): 2nd Time is: %"PRI64d" \n",last120BlockTimes[1]);*/

            //If the average time between blocks exceeds or is equal to 3 minutes then increase the med time accordingly
            if(height < julyFork2) {
                if (averageTime >= 180) {
                    log.info(" \n Average Time between blocks is too high.. Attempting to Adjust.. \n ");
                    medTime = 130;
                } else if (averageTime >= 108 && medTime < 120) {
                    //If the average time between blocks is more than 1.8 minutes and medTime is less than 120 seconds (which would ordinarily prompt an increase in difficulty)
                    //limit the stepping to something reasonable(so we don't see massive difficulty spike followed by miners leaving in these situations).
                    medTime = 110;
                    log.info(" \n Medium Time between blocks is too low compared to average time.. Attempting to Adjust.. \n ");
                }
            }
            else
            {
               medTime = (medTime > averageTime)? averageTime : medTime;
               if(averageTime >= 180 && last119TimeDifferences[0] >= 1200 && last119TimeDifferences[1] >= 1200) {
                    didHalfAdjust = true;
                   medTime = 240;
               }
            }
        }

        if(GoldcoinDefinition.usingMedianDifficultyProtocol(height))
        {
            Arrays.sort(last59BlockSolvingTimes);
            timespan = Math.abs(last59BlockSolvingTimes[29])*60;

            if(GoldcoinDefinition.usingMedianDifficultyProtocol2(height)) {
                if((last59BlockSolvingTimes[29]) >= 120) {
                    //Check to see whether we are in a deadlock situation with the 51% defense system

                    int numTooClose = 0;
                    int index = 1;
                    while(index != 55) {
                        if(Math.abs(last60BlockTimes[last60BlockTimes.length-index] - last60BlockTimes[last60BlockTimes.length-(index+5)]) == 600) {
                            numTooClose++;
                        }
                        index++;
                    }

                    if(numTooClose > 0) {
                        //We found 6 blocks that were solved in exactly 10 minutes
                        //Averaging 1.66 minutes per block


                        medTime = 110;
                    }


                }
            }
            timespan = medTime * 60;

        }
        // Limit the adjustment step.
        /* old version here
        if (timespan < params.targetTimespan / 4)
            timespan = params.targetTimespan / 4;
        if (timespan > params.targetTimespan * 4)
            timespan = params.targetTimespan * 4;
        */
        /*if (timespan < params.getTargetTimespan(height) / 4)
            timespan = params.getTargetTimespan(height) / 4;
        if (timespan > params.getTargetTimespan(height) * 4)
            timespan = params.getTargetTimespan(height) * 4;
        */
        int nTargetTimespanCurrent = params.getTargetTimespan(height);

        int nActualTimespanMax = GoldcoinDefinition.usingNewDifficultyProtocol(height)? ((nTargetTimespanCurrent*99)/70) : (nTargetTimespanCurrent*4);
        int nActualTimespanMin = GoldcoinDefinition.usingNewDifficultyProtocol(height)? ((nTargetTimespanCurrent*70)/99) : (nTargetTimespanCurrent/4);

        if (timespan < nActualTimespanMin)
            timespan = nActualTimespanMin;
        if (timespan > nActualTimespanMax)
            timespan = nActualTimespanMax;

        BigInteger newDifficulty = Utils.decodeCompactBits(prev.getDifficultyTarget());
        newDifficulty = newDifficulty.multiply(BigInteger.valueOf(timespan));
        newDifficulty = newDifficulty.divide(BigInteger.valueOf(nTargetTimespanCurrent));

        if (newDifficulty.compareTo(params.proofOfWorkLimit) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newDifficulty.toString(16));
            newDifficulty = params.proofOfWorkLimit;
        }

        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        BigInteger receivedDifficulty = nextBlock.getDifficultyTargetAsInteger();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        newDifficulty = newDifficulty.and(mask);

        if (newDifficulty.compareTo(receivedDifficulty) != 0)
            throw new VerificationException("Network provided difficulty bits do not match what was calculated: " +
                    receivedDifficulty.toString(16) + " vs " + newDifficulty.toString(16));
    }
    void verifyDifficulty(BigInteger newDifficulty, Block nextBlock) throws VerificationException
    {
        if (newDifficulty.compareTo(params.proofOfWorkLimit) > 0) {
            log.info("Difficulty hit proof of work limit: {}", newDifficulty.toString(16));
            newDifficulty = params.proofOfWorkLimit;
        }

        int accuracyBytes = (int) (nextBlock.getDifficultyTarget() >>> 24) - 3;
        BigInteger receivedDifficulty = nextBlock.getDifficultyTargetAsInteger();

        // The calculated difficulty is to a higher precision than received, so reduce here.
        BigInteger mask = BigInteger.valueOf(0xFFFFFFL).shiftLeft(accuracyBytes * 8);
        newDifficulty = newDifficulty.and(mask);

        if (newDifficulty.compareTo(receivedDifficulty) != 0)
            throw new VerificationException("Network provided difficulty bits do not match what was calculated: received" +
                    receivedDifficulty.toString(16) + " vs calculated " + newDifficulty.toString(16));
    }
    //TODO::This will not work
    private void checkTestnetDifficulty(StoredBlock storedPrev, Block prev, Block next) throws VerificationException, BlockStoreException {
        checkState(lock.isLocked());
        // After 15th February 2012 the rules on the testnet change to avoid people running up the difficulty
        // and then leaving, making it too hard to mine a block. On non-difficulty transition points, easy
        // blocks are allowed if there has been a span of 20 minutes without one.
        final long timeDelta = next.getTimeSeconds() - prev.getTimeSeconds();
        // There is an integer underflow bug in litecoin-qt that means mindiff blocks are accepted when time
        // goes backwards.
        if (timeDelta >= 0 && timeDelta <= NetworkParameters.TARGET_SPACING * 2) {
            // Walk backwards until we find a block that doesn't have the easiest proof of work, then check
            // that difficulty is equal to that one.
            StoredBlock cursor = storedPrev;
            while (!cursor.getHeader().equals(params.genesisBlock) &&
                    cursor.getHeight() % params.interval != 0 &&
                    cursor.getHeader().getDifficultyTargetAsInteger().equals(params.proofOfWorkLimit))
                cursor = cursor.getPrev(blockStore);
            BigInteger cursorDifficulty = cursor.getHeader().getDifficultyTargetAsInteger();
            BigInteger newDifficulty = next.getDifficultyTargetAsInteger();
            if (!cursorDifficulty.equals(newDifficulty))
                throw new VerificationException("Testnet block transition that is not allowed: " +
                        Long.toHexString(cursor.getHeader().getDifficultyTarget()) + " vs " +
                        Long.toHexString(next.getDifficultyTarget()));
        }
    }
    static final long julyFork = 45000;
    static final long novemberFork = 103000;
    static final long novemberFork2 = 118800;
    static final long mayFork = 248000;
    static final long octoberFork = 100000;

    static final long julyFork2 = 251230;

    static boolean hardForkedJuly;
    static boolean hardForkedNovember;

    static final long nTargetTimespan = (2 * 60 * 60);// Difficulty changes every 60 blocks
    static final long nTargetSpacing = 2 * 60;


    private void checkDifficultyTransitions(StoredBlock pindexLast, Block pblock) throws BlockStoreException, VerificationException {
        checkState(lock.isLocked());
        boolean fTestNet = params.getId().equals(NetworkParameters.ID_TESTNET);
        Block prev = pindexLast.getHeader();

        //Todo:: Clean this mess up.. -akumaburn
        BigInteger bnProofOfWorkLimit = params.proofOfWorkLimit;
        BigInteger bnNew;



        // Genesis block
        if (pindexLast == null) {
            verifyDifficulty(params.proofOfWorkLimit, pblock);
            return;
        }

        // FeatherCoin difficulty adjustment protocol switch
        final int nDifficultySwitchHeight = 21000;
        int nHeight = pindexLast.getHeight() + 1;
        boolean fNewDifficultyProtocol = (nHeight >= nDifficultySwitchHeight || fTestNet);

        //julyFork2 whether or not we had a massive difficulty fall authorized
        boolean didHalfAdjust = false;

        //moved to solve scope issues
        long averageTime = 120;

        if(nHeight < julyFork) {
            //if(!hardForkedJuly) {
            long nTargetTimespan2 = (7 * 24 * 60 * 60) / 8;
            long nTargetSpacing2 = (long)(2.5 * 60);

            long nTargetTimespan2Current = fNewDifficultyProtocol? nTargetTimespan2 : (nTargetTimespan2*4);
            long nInterval = nTargetTimespan2Current / nTargetSpacing2;

            // Only change once per interval, or at protocol switch height
            if ((nHeight % nInterval != 0) &&
                    (nHeight != nDifficultySwitchHeight || fTestNet))
            {
                // Special difficulty rule for testnet:
                if (fTestNet)
                {
                    // If the new block's timestamp is more than 2* 10 minutes
                    // then allow mining of a min-difficulty block.
                    if (pblock.getTimeSeconds() > pindexLast.getHeader().getTimeSeconds() + nTargetSpacing2*2) {
                        verifyDifficulty(bnProofOfWorkLimit, pblock);
                        return;
                    }
                    else
                    {
                        // Return the last non-special-min-difficulty-rules-block
                        StoredBlock cursor = pindexLast;

                        while((cursor = cursor.getPrev(blockStore))!= null && cursor.getHeight() % nInterval != 0 && !cursor.getHeader().getDifficultyTargetAsInteger().equals(bnProofOfWorkLimit))
                        {
                            cursor = cursor.getPrev(blockStore);
                            if(cursor == null)
                                return;
                        }
                        verifyDifficulty(pindexLast.getHeader().getDifficultyTargetAsInteger(), pblock);
                        return;
                    }
                }

                verifyDifficulty(pindexLast.getHeader().getDifficultyTargetAsInteger(), pblock);
                return;
            }

            // GoldCoin (GLD): This fixes an issue where a 51% attack can change difficulty at will.
            // Go back the full period unless it's the first retarget after genesis. Code courtesy of Art Forz
            long blockstogoback = nInterval-1;
            if ((pindexLast.getHeight()+1) != nInterval)
                blockstogoback = nInterval;

            StoredBlock pindexFirst = pindexLast;
            for (int i = 0; pindexFirst != null && i < blockstogoback; i++) {
                //pindexFirst = pindexFirst -> pprev;
                pindexFirst = pindexFirst.getPrev(blockStore);

                if(pindexFirst == null)
                    return;
            }
            //assert(pindexFirst);

            // Limit adjustment step
            long nActualTimespan = pindexLast.getHeader().getTimeSeconds() - pindexFirst.getHeader().getTimeSeconds();
            log.info("  nActualTimespan = %d  before bounds\n", nActualTimespan);
            long nActualTimespanMax = fNewDifficultyProtocol? ((nTargetTimespan2Current*99)/70) : (nTargetTimespan2Current*4);
            long nActualTimespanMin = fNewDifficultyProtocol? ((nTargetTimespan2Current*70)/99) : (nTargetTimespan2Current/4);
            if (nActualTimespan < nActualTimespanMin)
                nActualTimespan = nActualTimespanMin;
            if (nActualTimespan > nActualTimespanMax)
                nActualTimespan = nActualTimespanMax;
            // Retarget
            bnNew = pindexLast.getHeader().getDifficultyTargetAsInteger();
            //bnNew *= nActualTimespan;
            bnNew = bnNew.multiply(BigInteger.valueOf(nActualTimespan));
            //bnNew /= nTargetTimespan2Current;
            bnNew = bnNew.divide(BigInteger.valueOf(nTargetTimespan2Current));


            //if (bnNew.compareTo(bnProofOfWorkLimit) < 0)
            //    bnNew = bnProofOfWorkLimit;

            /// debug print
            //log.info("GetNextWorkRequired RETARGET\n");
            //log.info("nTargetTimespan2 = %d    nActualTimespan = %\n", nTargetTimespan2Current, nActualTimespan);
            //log.info("Before: %08x  %s\n", pindexLast->nBits, CBigNum().SetCompact(pindexLast->nBits).getuint256().ToString().c_str());
            //log.info("After:  %08x  %s\n", bnNew.GetCompact(), bnNew.getuint256().ToString().c_str());
        } else if(nHeight > novemberFork) {
            hardForkedNovember = true;

            long nTargetTimespanCurrent = fNewDifficultyProtocol? nTargetTimespan : (nTargetTimespan*4);
            long nInterval = nTargetTimespanCurrent / nTargetSpacing;

            // Only change once per interval, or at protocol switch height
            // After julyFork2 we change difficulty at every block.. so we want this only to happen before that..
            if ((nHeight % nInterval != 0) &&
                    (nHeight != nDifficultySwitchHeight || fTestNet) && (nHeight <= julyFork2))
            {
                // Special difficulty rule for testnet:
                if (fTestNet)
                {
                    // If the new block's timestamp is more than 2* 10 minutes
                    // then allow mining of a min-difficulty block.
                    if (pblock.getTimeSeconds() > pindexLast.getHeader().getTimeSeconds() + nTargetSpacing*2) {
                        verifyDifficulty(bnProofOfWorkLimit, pblock);
                        return;
                    }
                    else
                    {
                        // Return the last non-special-min-difficulty-rules-block
                        StoredBlock cursor = pindexLast;

                        while((cursor = cursor.getPrev(blockStore))!= null && cursor.getHeight() % nInterval != 0 && !cursor.getHeader().getDifficultyTargetAsInteger().equals(bnProofOfWorkLimit))
                        {
                            cursor = cursor.getPrev(blockStore);
                            if(cursor == null)
                                return;
                        }
                        verifyDifficulty(pindexLast.getHeader().getDifficultyTargetAsInteger(), pblock);
                        return;
                    }
                }

                verifyDifficulty(pindexLast.getHeader().getDifficultyTargetAsInteger(), pblock);
                return;
            }

            // GoldCoin (GLD): This fixes an issue where a 51% attack can change difficulty at will.
            // Go back the full period unless it's the first retarget after genesis. Code courtesy of Art Forz
            long blockstogoback = nInterval-1;
            if ((pindexLast.getHeight()+1) != nInterval)
                blockstogoback = nInterval;

            StoredBlock pindexFirst = pindexLast;
            for (int i = 0; pindexFirst != null && i < blockstogoback; i++) {
                //pindexFirst = pindexFirst -> pprev;
                pindexFirst = pindexFirst.getPrev(blockStore);

                if(pindexFirst == null)
                    return;
            }

            StoredBlock tblock1 = pindexLast;//We want to copy pindexLast to avoid changing it accidentally
            StoredBlock tblock2 = tblock1;

            //std::vector<int64> last60BlockTimes;
            ArrayList<Long> last60BlockTimes = new ArrayList<Long>(60);
            // Limit adjustment step
            //We need to set this in a way that reflects how fast blocks are actually being solved..
            //First we find the last 60 blocks and take the time between blocks
            //That gives us a list of 59 time differences
            //Then we take the median of those times and multiply it by 60 to get our actualtimespan
            while(last60BlockTimes.size() < 60) {
                last60BlockTimes.add(tblock2.getHeader().getTimeSeconds());
                //if(tblock2->pprev)//should always be so
                //    tblock2 = tblock2->pprev;
                tblock2 = tblock2.getPrev(blockStore);
                if(tblock2 == null)
                    return;
            }
            //std::vector<int64> last59TimeDifferences;
            ArrayList<Long> last59TimeDifferences = new ArrayList<Long>(59);

            int xy = 0;
            while(last59TimeDifferences.size() != 59) {
                if(xy == 59) {
                    //printf(" GetNextWorkRequired(): This shouldn't have happened \n");
                    break;
                }
                last59TimeDifferences.add(java.lang.Math.abs(last60BlockTimes.get(xy) - last60BlockTimes.get(xy+1)));
                xy++;
            }
            Collections.sort(last59TimeDifferences);
            //sort(last59TimeDifferences.begin(), last59TimeDifferences.end(), comp64);

            log.info("  Median Time between blocks is: %d \n",last59TimeDifferences.get(29));
            long nActualTimespan = java.lang.Math.abs((last59TimeDifferences.get(29)));
            long medTime = nActualTimespan;

            if(nHeight > mayFork) {


                //Difficulty Fix here for case where average time between blocks becomes far longer than 2 minutes, even though median time is close to 2 minutes.
                //Uses the last 120 blocks(Should be 4 hours) for calculating

                //log.info(" GetNextWorkRequired(): May Fork mode \n");

                tblock1 = pindexLast;//We want to copy pindexLast to avoid changing it accidentally
                tblock2 = tblock1;

                ArrayList<Long> last120BlockTimes = new ArrayList<Long>();
                // Limit adjustment step
                //We need to set this in a way that reflects how fast blocks are actually being solved..
                //First we find the last 120 blocks and take the time between blocks
                //That gives us a list of 119 time differences
                //Then we take the average of those times and multiply it by 60 to get our actualtimespan
                while(last120BlockTimes.size() < 120) {
                    last120BlockTimes.add(tblock2.getHeader().getTimeSeconds());
                    tblock2 = tblock2.getPrev(blockStore);
                    if(tblock2 == null)
                        return;
                }
                ArrayList<Long> last119TimeDifferences = new ArrayList<Long>();

                xy = 0;
                while(last119TimeDifferences.size() != 119) {
                    if(xy == 119) {
                 //       printf(" GetNextWorkRequired(): This shouldn't have happened 2 \n");
                        break;
                    }
                    last119TimeDifferences.add(java.lang.Math.abs(last120BlockTimes.get(xy) - last120BlockTimes.get(xy + 1)));
                    xy++;
                }
                long total = 0;

                for(int x = 0; x < 119; x++) {
                    long timeN = last119TimeDifferences.get(x);
                    //printf(" GetNextWorkRequired(): Current Time difference is: %"PRI64d" \n",timeN);
                    total += timeN;
                }

                averageTime = total/119;


                log.info(" GetNextWorkRequired(): Average time between blocks over the last 120 blocks is: "+ averageTime);
            /*printf(" GetNextWorkRequired(): Total Time (over 119 time differences) is: %"PRI64d" \n",total);
            printf(" GetNextWorkRequired(): First Time (over 119 time differences) is: %"PRI64d" \n",last119TimeDifferences[0]);
            printf(" GetNextWorkRequired(): Last Time (over 119 time differences) is: %"PRI64d" \n",last119TimeDifferences[118]);
            printf(" GetNextWorkRequired(): Last Time is: %"PRI64d" \n",last120BlockTimes[119]);
            printf(" GetNextWorkRequired(): 2nd Last Time is: %"PRI64d" \n",last120BlockTimes[118]);

            printf(" GetNextWorkRequired(): First Time is: %"PRI64d" \n",last120BlockTimes[0]);
            printf(" GetNextWorkRequired(): 2nd Time is: %"PRI64d" \n",last120BlockTimes[1]);*/

                if(nHeight <= julyFork2) {
                    //If the average time between blocks exceeds or is equal to 3 minutes then increase the med time accordingly
                    if(averageTime >= 180) {
                        log.info(" \n Average Time between blocks is too high.. Attempting to Adjust.. \n ");
                        medTime = 130;
                    } else if(averageTime >= 108 && medTime < 120) {
                        //If the average time between blocks is more than 1.8 minutes and medTime is less than 120 seconds (which would ordinarily prompt an increase in difficulty)
                        //limit the stepping to something reasonable(so we don't see massive difficulty spike followed by miners leaving in these situations).
                        medTime = 110;
                        log.info(" \n Medium Time between blocks is too low compared to average time.. Attempting to Adjust.. \n ");
                    }
                } else {//julyFork2 changes here

                    //Calculate difficulty of previous block as a double
                /*int nShift = (pindexLast->nBits >> 24) & 0xff;

				double dDiff =
					(double)0x0000ffff / (double)(pindexLast->nBits & 0x00ffffff);

				while (nShift < 29)
				{
					dDiff *= 256.0;
					nShift++;
				}
				while (nShift > 29)
				{
					dDiff /= 256.0;
					nShift--;
                } */

                    //int64 hashrate = (int64)(dDiff * pow(2.0,32.0))/((medTime > averageTime)?averageTime:medTime);

                    medTime = (medTime > averageTime)?averageTime:medTime;

                    if(averageTime >= 180 && last119TimeDifferences.get(0) >= 1200 && last119TimeDifferences.get(1) >= 1200) {
                        didHalfAdjust = true;
                        medTime = 240;
                    }

                }
            }

            //Fixes an issue where median time between blocks is greater than 120 seconds and is not permitted to be lower by the defence system
            //Causing difficulty to drop without end

            if(nHeight > novemberFork2) {
                if(medTime >= 120) {
                    //Check to see whether we are in a deadlock situation with the 51% defense system
                    //printf(" \n Checking for DeadLocks \n");
                    int numTooClose = 0;
                    int index = 1;
                    while(index != 55) {
                        if(java.lang.Math.abs(last60BlockTimes.get(last60BlockTimes.size()-index) - last60BlockTimes.get(last60BlockTimes.size() - (index + 5))) == 600) {
                            numTooClose++;
                        }
                        index++;
                    }

                    if(numTooClose > 0) {
                        //We found 6 blocks that were solved in exactly 10 minutes
                        //Averaging 1.66 minutes per block
                        //printf(" \n DeadLock detected and fixed - Difficulty Increased to avoid bleeding edge of defence system \n");

                        if(nHeight > julyFork2) {
                            medTime = 119;
                        } else {
                            medTime = 110;
                        }
                    } else {
                        //printf(" \n DeadLock not detected. \n");
                    }


                }
            }


            if(nHeight > julyFork2) {
                //216 == (int64) 180.0/100.0 * 120
                //122 == (int64) 102.0/100.0 * 120 == 122.4
                if(averageTime > 216 || medTime > 122) {
                    if(didHalfAdjust) {
                        // If the average time between blocks was
                        // too high.. allow a dramatic difficulty
                        // fall..
                        medTime = (long)(120 * 142.0/100.0);
                    } else {
                        // Otherwise only allow a 120/119 fall per block
                        // maximum.. As we now adjust per block..
                        // 121 == (int64) 120 * 120.0/119.0
                        medTime = 121;
                    }
                }
                // 117 -- (int64) 120.0 * 98.0/100.0
                else if(averageTime < 117 || medTime < 117)  {
                    // If the average time between blocks is within 2% of target
                    // value
                    // Or if the median time stamp between blocks is within 2% of
                    // the target value
                    // Limit diff increase to 2%
                    medTime = 117;
                }
                nActualTimespan = medTime * 60;
            } else {

                nActualTimespan = medTime * 60;

                //printf("  nActualTimespan = %"PRI64d"  before bounds\n", nActualTimespan);
                long nActualTimespanMax = fNewDifficultyProtocol? ((nTargetTimespanCurrent*99)/70) : (nTargetTimespanCurrent*4);
                long nActualTimespanMin = fNewDifficultyProtocol? ((nTargetTimespanCurrent*70)/99) : (nTargetTimespanCurrent/4);
                if (nActualTimespan < nActualTimespanMin)
                    nActualTimespan = nActualTimespanMin;
                if (nActualTimespan > nActualTimespanMax)
                    nActualTimespan = nActualTimespanMax;

            }


            if(nHeight > julyFork2) {
                StoredBlock tblock11 = pindexLast;//We want to copy pindexLast to avoid changing it accidentally
                StoredBlock tblock22 = tblock11;

                // We want to limit the possible difficulty raise/fall over 60 and 240 blocks here
                // So we get the difficulty at 60 and 240 blocks ago

                long nbits60ago = 0;
                long nbits240ago = 0;
                int counter = 0;
                //Note: 0 is the current block, we want 60 past current
                while(counter <= 240) {
                    if(counter == 60) {
                        nbits60ago = tblock22.getHeader().getDifficultyTarget();
                    } else if(counter == 240) {
                        nbits240ago = tblock22.getHeader().getDifficultyTarget();
                    }
                    tblock22 = tblock22.getPrev(blockStore);

                    counter++;
                }

                //Now we get the old targets
                BigInteger bn60ago;
                BigInteger bn240ago;
                BigInteger bnLast;

                bn60ago = Utils.decodeCompactBits(nbits60ago);
                bn240ago = Utils.decodeCompactBits(nbits240ago);
                bnLast = pindexLast.getHeader().getDifficultyTargetAsInteger();

                //Set the new target
                bnNew = pindexLast.getHeader().getDifficultyTargetAsInteger();
                //bnNew *= nActualTimespan;
                bnNew = bnNew.multiply(BigInteger.valueOf(nActualTimespan));
                //bnNew /= nTargetTimespanCurrent;
                bnNew = bnNew.divide(BigInteger.valueOf(nTargetTimespanCurrent));


                //Now we have the difficulty at those blocks..

                // Set a floor on difficulty decreases per block(20% lower maximum
                // than the previous block difficulty).. when there was no halfing
                // necessary.. 10/8 == 1.0/0.8
                //bnLast *= 10;
                bnLast = bnLast.multiply(BigInteger.valueOf(10));
                //bnLast /= 8;
                bnLast = bnLast.divide(BigInteger.valueOf(8));

                if(!didHalfAdjust && bnNew.compareTo(bnLast) > 0) {
                    bnNew = bnLast;
                }

                //bnLast *= 8;
                bnLast = bnLast.multiply(BigInteger.valueOf(8));
                //bnLast /= 10;
                bnLast = bnLast.divide(BigInteger.valueOf(10));

                // Set ceilings on difficulty increases per block

                //1.0/1.02 == 100/102
                //bn60ago *= 100;
                bn60ago = bn60ago.multiply(BigInteger.valueOf(100));
                //bn60ago /= 102;
                bn60ago = bn60ago.divide(BigInteger.valueOf(102));

                if(bnNew.compareTo(bn60ago) < 0) {
                    bnNew = bn60ago;
                }

//                bn60ago *= 102;
  //              bn60ago /= 100;
                bn60ago = bn60ago.multiply(BigInteger.valueOf(102));
                bn60ago = bn60ago.divide(BigInteger.valueOf(100));

                //1.0/(1.02*4) ==  100 / 408

                //bn240ago *= 100;
                //bn240ago /= 408;
                bn240ago = bn240ago.multiply(BigInteger.valueOf(100));
                bn240ago = bn240ago.divide(BigInteger.valueOf(408));

                if(bnNew.compareTo(bn240ago) < 0) {
                    bnNew = bn240ago;
                }

                //bn240ago *= 408;
                //bn240ago /= 100;
                bn240ago = bn240ago.multiply(BigInteger.valueOf(408));
                bn240ago = bn240ago.divide(BigInteger.valueOf(100));


            } else {
                // Retarget
                bnNew = pindexLast.getHeader().getDifficultyTargetAsInteger();
                //bnNew *= nActualTimespan;
                //bnNew /= nTargetTimespanCurrent;
                bnNew = bnNew.multiply(BigInteger.valueOf(nActualTimespan));
                bnNew = bnNew.divide(BigInteger.valueOf(nTargetTimespanCurrent));
            }

            //Sets a ceiling on highest target value (lowest possible difficulty)
            if (bnNew.compareTo(bnProofOfWorkLimit) > 0)
                bnNew = bnProofOfWorkLimit;

            /// debug print
            //printf("GetNextWorkRequired RETARGET\n");
            //printf("nTargetTimespan = %"PRI64d"    nActualTimespan = %"PRI64d"\n", nTargetTimespanCurrent, nActualTimespan);
            //printf("Before: %08x  %s\n", pindexLast->nBits, CBigNum().SetCompact(pindexLast->nBits).getuint256().ToString().c_str());
            //printf("After:  %08x  %s\n", bnNew.GetCompact(), bnNew.getuint256().ToString().c_str());
        } else {
            hardForkedJuly = true;
            long nTargetTimespanCurrent = fNewDifficultyProtocol? nTargetTimespan : (nTargetTimespan*4);
            long nInterval = nTargetTimespanCurrent / nTargetSpacing;

            // Only change once per interval, or at protocol switch height
            if ((nHeight % nInterval != 0) &&
                    (nHeight != nDifficultySwitchHeight || fTestNet))
            {
                // Special difficulty rule for testnet:
                if (fTestNet)
                {
                    // If the new block's timestamp is more than 2* 10 minutes
                    // then allow mining of a min-difficulty block.
                    if (pblock.getTimeSeconds() > pindexLast.getHeader().getTimeSeconds() + nTargetSpacing*2) {
                        verifyDifficulty(bnProofOfWorkLimit, pblock);
                        return;
                    }
                    else
                    {
                        // Return the last non-special-min-difficulty-rules-block
                        // Return the last non-special-min-difficulty-rules-block
                        StoredBlock cursor = pindexLast;

                        while((cursor = cursor.getPrev(blockStore))!= null && cursor.getHeight() % nInterval != 0 && !cursor.getHeader().getDifficultyTargetAsInteger().equals(bnProofOfWorkLimit))
                        {
                            cursor = cursor.getPrev(blockStore);
                            if(cursor == null)
                                return;
                        }
                        verifyDifficulty(pindexLast.getHeader().getDifficultyTargetAsInteger(), pblock);
                        return;
                    }
                }

                verifyDifficulty(pindexLast.getHeader().getDifficultyTargetAsInteger(), pblock);
                return;
            }

            // GoldCoin (GLD): This fixes an issue where a 51% attack can change difficulty at will.
            // Go back the full period unless it's the first retarget after genesis. Code courtesy of Art Forz
            long blockstogoback = nInterval-1;
            if ((pindexLast.getHeight()+1) != nInterval)
                blockstogoback = nInterval;
            StoredBlock pindexFirst = pindexLast;
            for (int i = 0; pindexFirst != null && i < blockstogoback; i++) {
                pindexFirst = pindexFirst.getPrev(blockStore);
                if(pindexFirst == null)
                    return;
            }
            //assert(pindexFirst);

            // Limit adjustment step
            long nActualTimespan = pindexLast.getHeader().getTimeSeconds() - pindexFirst.getHeader().getTimeSeconds();
            //printf("  nActualTimespan = %"PRI64d"  before bounds\n", nActualTimespan);
            long nActualTimespanMax = fNewDifficultyProtocol? ((nTargetTimespanCurrent*99)/70) : (nTargetTimespanCurrent*4);
            long nActualTimespanMin = fNewDifficultyProtocol? ((nTargetTimespanCurrent*70)/99) : (nTargetTimespanCurrent/4);
            if (nActualTimespan < nActualTimespanMin)
                nActualTimespan = nActualTimespanMin;
            if (nActualTimespan > nActualTimespanMax)
                nActualTimespan = nActualTimespanMax;
            // Retarget
            bnNew = pindexLast.getHeader().getDifficultyTargetAsInteger();
            //bnNew *= nActualTimespan;
            //bnNew /= nTargetTimespanCurrent;
            bnNew = bnNew.multiply(BigInteger.valueOf(nActualTimespan));
            bnNew = bnNew.divide(BigInteger.valueOf(nTargetTimespanCurrent));

            //if (bnNew > bnProofOfWorkLimit)
              //  bnNew = bnProofOfWorkLimit;

            /// debug print
          //  printf("GetNextWorkRequired RETARGET\n");
           // printf("nTargetTimespan = %"PRI64d"    nActualTimespan = %"PRI64d"\n", nTargetTimespanCurrent, nActualTimespan);
           // printf("Before: %08x  %s\n", pindexLast->nBits, CBigNum().SetCompact(pindexLast->nBits).getuint256().ToString().c_str());
            //printf("After:  %08x  %s\n", bnNew.GetCompact(), bnNew.getuint256().ToString().c_str());
        }
        //return bnNew.GetCompact();
        verifyDifficulty(bnNew, pblock);
        return;
    }

    /**
     * Returns true if any connected wallet considers any transaction in the block to be relevant.
     */
    private boolean containsRelevantTransactions(Block block) {
        // Does not need to be locked.
        for (Transaction tx : block.transactions) {
            try {
                for (BlockChainListener listener : listeners) {
                    if (listener.isTransactionRelevant(tx)) return true;
                }
            } catch (ScriptException e) {
                // We don't want scripts we don't understand to break the block chain so just note that this tx was
                // not scanned here and continue.
                log.warn("Failed to parse a script: " + e.toString());
            }
        }
        return false;
    }

    /**
     * Returns the block at the head of the current best chain. This is the block which represents the greatest
     * amount of cumulative work done.
     */
    public StoredBlock getChainHead() {
        synchronized (chainHeadLock) {
            return chainHead;
        }
    }

    /**
     * An orphan block is one that does not connect to the chain anywhere (ie we can't find its parent, therefore
     * it's an orphan). Typically this occurs when we are downloading the chain and didn't reach the head yet, and/or
     * if a block is solved whilst we are downloading. It's possible that we see a small amount of orphan blocks which
     * chain together, this method tries walking backwards through the known orphan blocks to find the bottom-most.
     *
     * @return from or one of froms parents, or null if "from" does not identify an orphan block
     */
    public Block getOrphanRoot(Sha256Hash from) {
        lock.lock();
        try {
            OrphanBlock cursor = orphanBlocks.get(from);
            if (cursor == null)
                return null;
            OrphanBlock tmp;
            while ((tmp = orphanBlocks.get(cursor.block.getPrevBlockHash())) != null) {
                cursor = tmp;
            }
            return cursor.block;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns true if the given block is currently in the orphan blocks list.
     */
    public boolean isOrphan(Sha256Hash block) {
        lock.lock();
        try {
            return orphanBlocks.containsKey(block);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an estimate of when the given block will be reached, assuming a perfect 10 minute average for each
     * block. This is useful for turning transaction lock times into human readable times. Note that a height in
     * the past will still be estimated, even though the time of solving is actually known (we won't scan backwards
     * through the chain to obtain the right answer).
     */
    public Date estimateBlockTime(int height) {
        synchronized (chainHeadLock) {
            long offset = height - chainHead.getHeight();
            long headTime = chainHead.getHeader().getTimeSeconds();
            long estimated = (headTime * 1000) + (1000L * 60L * 10L * offset);
            return new Date(estimated);
        }
    }


    }
