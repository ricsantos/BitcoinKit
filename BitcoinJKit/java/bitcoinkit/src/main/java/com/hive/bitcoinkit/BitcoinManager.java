package com.hive.bitcoinkit;

import static com.google.bitcoin.core.Utils.bytesToHexString;

import com.google.bitcoin.core.*;
import com.google.bitcoin.crypto.KeyCrypterException;
import com.google.bitcoin.discovery.DnsDiscovery;
import com.google.bitcoin.params.MainNetParams;
import com.google.bitcoin.params.RegTestParams;
import com.google.bitcoin.params.TestNet3Params;
import com.google.bitcoin.script.Script;
import com.google.bitcoin.store.BlockStore;
import com.google.bitcoin.store.SPVBlockStore;
import com.google.bitcoin.store.UnreadableWalletException;
import com.google.bitcoin.utils.BriefLogFormatter;
import com.google.bitcoin.utils.Threading;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class BitcoinManager implements PeerEventListener, Thread.UncaughtExceptionHandler {
	private NetworkParameters networkParams;
	private Wallet wallet;
	private String dataDirectory;
	
    private PeerGroup peerGroup;
    private BlockStore blockStore;
    private File walletFile;
    private int blocksToDownload;

    public BitcoinManager()
    {
        Threading.uncaughtExceptionHandler = this;
    }

	public void setTestingNetwork(boolean testing)
	{
		if (testing)
		{
			this.networkParams = TestNet3Params.get();
		}
		else
		{
			this.networkParams = MainNetParams.get();
		}
	}
	
	public void setDataDirectory(String path)
	{
		dataDirectory = path;
	}
	
	public String getWalletAddress()
	{
		ECKey ecKey = wallet.getKeys().get(0);
		return ecKey.toAddress(networkParams).toString();
	}

    public String getWalletDebuggingInfo()
    {
        return (wallet != null) ? wallet.toString() : null;
    }

    public int getBalance()
    {
        return (wallet != null) ? wallet.getBalance().intValue() : 0;
    }

    public int getEstimatedBalance()
    {
        return (wallet != null) ? wallet.getBalance(Wallet.BalanceType.ESTIMATED).intValue() : 0;
    }

	private String getJSONFromTransaction(Transaction tx) throws ScriptException
	{
		if (tx == null)
        {
            return null;
        }

        StringBuffer conns = new StringBuffer();
        int connCount = 0;

        TransactionConfidence.ConfidenceType confidenceType = tx.getConfidence().getConfidenceType();
        String confidence;

        if (confidenceType == TransactionConfidence.ConfidenceType.BUILDING)
        {
            confidence = "building";
        }
        else if (confidenceType == TransactionConfidence.ConfidenceType.PENDING)
        {
            confidence = "pending";
        }
        else if (confidenceType == TransactionConfidence.ConfidenceType.DEAD)
        {
            confidence = "dead";
        }
        else
        {
            confidence = "unknown";
        }

        conns.append("[");

        if (tx.getInputs().size() > 0 && tx.getValue(wallet).compareTo(BigInteger.ZERO) > 0)
        {
            TransactionInput in = tx.getInput(0);

            if (connCount > 0)
            {
                conns.append(", ");
            }

            conns.append("{ ");

            try {
                Script scriptSig = in.getScriptSig();

                if (scriptSig.getChunks().size() == 2)
                {
                    conns.append("\"address\": \"" + scriptSig.getFromAddress(networkParams).toString() + "\"");
                }

                conns.append(" ,\"category\": \"received\" }");

                connCount++;
            } catch (Exception e) {

            }
        }

        if (tx.getOutputs().size() > 0 && tx.getValue(wallet).compareTo(BigInteger.ZERO) < 0)
        {
            TransactionOutput out = tx.getOutput(0);

            if (connCount > 0)
            {
                conns.append(", ");
            }

            conns.append("{ ");

            try {
                Script scriptPubKey = out.getScriptPubKey();

                if (scriptPubKey.isSentToAddress())
                {
                    conns.append(" \"address\": \"" + scriptPubKey.getToAddress(networkParams).toString() + "\"");
                }

                conns.append(" ,\"category\": \"sent\" }");

                connCount++;
            } catch (Exception e) {

            }
        }

        conns.append("]");

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));

        return "{ \"amount\": " + tx.getValue(wallet) +
                ", \"txid\": \"" + tx.getHashAsString()  + "\"" +
                ", \"time\": \""  + dateFormat.format(tx.getUpdateTime()) + "\"" +
                ", \"confidence\": \""   +confidence + "\"" +
                ", \"details\": "   + conns.toString() +
                "}";
	}

	public int getTransactionCount()
	{
		return wallet.getTransactionsByTime().size();
	}
    
    public String getAllTransactions() throws ScriptException
    {
        return getTransactions(0, getTransactionCount());
    }
	
	public String getTransaction(String tx) throws ScriptException
	{
		Sha256Hash hash = new Sha256Hash(tx);
		return getJSONFromTransaction(wallet.getTransaction(hash));
	}
	
	public String getTransaction(int idx) throws ScriptException
	{	
		return getJSONFromTransaction(wallet.getTransactionsByTime().get(idx));
	}
	
	public String getTransactions(int from, int count) throws ScriptException
	{
		List<Transaction> transactions = wallet.getTransactionsByTime();
		
		if (from >= transactions.size())
			return null;
		
		int to = (from + count < transactions.size()) ? from + count : transactions.size();
		
		StringBuffer txs = new StringBuffer();
		txs.append("[\n");
		boolean first = true;
		for (; from < to; from++)
		{
			if (first)
				first = false;
			else
				txs.append("\n,");
			
			txs.append(getJSONFromTransaction(transactions.get(from)));
		}
		txs.append("]\n");
		
		return txs.toString();
	}

	public String feeForSendingCoins(String amount) throws AddressFormatException {
		return Transaction.REFERENCE_DEFAULT_MIN_TX_FEE.toString();
	}

	public void sendCoins(String amount, final String sendToAddressString)
	{
		  try {
			  BigInteger aToSend = new BigInteger(amount);
			  Address sendToAddress = new Address(networkParams, sendToAddressString);
	          final Wallet.SendResult sendResult = wallet.sendCoins(peerGroup, sendToAddress, aToSend);
	          Futures.addCallback(sendResult.broadcastComplete, new FutureCallback<Transaction>() {
	               public void onSuccess(Transaction transaction) {
                       onTransactionSuccess(sendResult.tx.getHashAsString());
	            	   onTransactionChanged(sendResult.tx.getHashAsString());
	                }

	                public void onFailure(Throwable throwable) {
	                	
	                	onTransactionFailed();
	                    throwable.printStackTrace();
	                }
	            });
	       } catch (Exception e) {
	    	   onTransactionFailed();   	   
	       }		
	}

    public String getExceptionStackTrace(Throwable exception)
    {
        StringBuilder buffer = new StringBuilder();

        for (StackTraceElement line : exception.getStackTrace())
        {
            buffer.append("at " + line.toString() + "\n");
        }

        return buffer.toString();
    }

    public boolean isAddressValid(String address)
    {
        try {
            Address addr = new Address(networkParams, address);
            return (addr != null);
        }
        catch (Exception e)
        {
            return false;
        }
    }
	
	public void start() throws Exception
	{
        if (networkParams == null)
        {
            setTestingNetwork(false);
        }

        File chainFile = new File(dataDirectory + "/bitcoinkit.spvchain");

        // Try to read the wallet from storage, create a new one if not possible.
        wallet = null;
        walletFile = new File(dataDirectory + "/bitcoinkit.wallet");

        if (walletFile.exists()) {
            wallet = Wallet.loadFromFile(walletFile);
        } else {
            wallet = new Wallet(networkParams);
            wallet.addKey(new ECKey());
            wallet.saveToFile(walletFile);
        }

        //make wallet autosave
        wallet.autosaveToFile(walletFile, 1, TimeUnit.SECONDS, null);
        

        // Fetch the first key in the wallet (should be the only key).
        ECKey key = wallet.getKeys().iterator().next();

        // Load the block chain, if there is one stored locally. If it's going to be freshly created, checkpoint it.
        boolean chainExistedAlready = chainFile.exists();
        blockStore = new SPVBlockStore(networkParams, chainFile);
        if (!chainExistedAlready) {
            File checkpointsFile = new File(dataDirectory + "/bitcoinkit.checkpoints");
            if (checkpointsFile.exists()) {
                FileInputStream stream = new FileInputStream(checkpointsFile);
                CheckpointManager.checkpoint(networkParams, stream, blockStore, key.getCreationTimeSeconds());
            }
        }
     
        BlockChain chain = new BlockChain(networkParams, wallet, blockStore);
        // Connect to the localhost node. One minute timeout since we won't try any other peers
        peerGroup = new PeerGroup(networkParams, chain);
        peerGroup.setUserAgent("BitcoinJKit", "0.9");
        if (networkParams == RegTestParams.get()) {
            peerGroup.addAddress(InetAddress.getLocalHost());
        } else {
            peerGroup.addPeerDiscovery(new DnsDiscovery(networkParams));
        }
        peerGroup.addWallet(wallet);
        

        // We want to know when the balance changes.
        wallet.addEventListener(new AbstractWalletEventListener() {
            @Override
            public void onCoinsReceived(Wallet w, Transaction tx, BigInteger prevBalance, BigInteger newBalance) {
                assert !newBalance.equals(BigInteger.ZERO);
                if (!tx.isPending()) return;
                // It was broadcast, but we can't really verify it's valid until it appears in a block.
                BigInteger value = tx.getValueSentToMe(w);
                onTransactionChanged(tx.getHashAsString());
                tx.getConfidence().addEventListener(new TransactionConfidence.Listener() {
                    public void onConfidenceChanged(final Transaction tx2, TransactionConfidence.Listener.ChangeReason reason) {
                        if (tx2.getConfidence().getConfidenceType() == TransactionConfidence.ConfidenceType.BUILDING) {
                            // Coins were confirmed (appeared in a block).
                            tx2.getConfidence().removeEventListener(this);
                        } else {
//                            System.out.println(String.format("Confidence of %s changed, is now: %s",
//                                    tx2.getHashAsString(), tx2.getConfidence().toString()));
                        }
                        onTransactionChanged(tx2.getHashAsString());
                    }
                });
            }
        });   
        
        peerGroup.startAndWait();
        peerGroup.start();

        onBalanceChanged();

        peerGroup.startBlockChainDownload(this);

	}

    public void uncaughtException(Thread thread, Throwable exception)
    {
        onException(exception);
    }

	public void stop()
	{
		try {
            System.out.print("Shutting down ... ");

            if (peerGroup != null)
            {
                peerGroup.stopAndWait();
            }

            if (wallet != null)
            {
                wallet.saveToFile(walletFile);
            }

            if (blockStore != null)
            {
                blockStore.close();
            }

            System.out.print("done ");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
	}
	
	public void walletExport(String path)
	{
		
	}


	/* Implementing native callbacks here */
	
	public native void onTransactionChanged(String txid);
    
	public native void onTransactionFailed();
	
    public native void onTransactionSuccess(String txid);
    
	public native void onSynchronizationUpdate(int percent);

	public native void onBalanceChanged();

    public native void onException(Throwable exception);


	/* Implementing peer listener */

    public void onPeerCountChange(int peersConnected)
	{
        //		System.out.println("Peers " + peersConnected);
	}

    public void onBlocksDownloaded(Peer peer, Block block, int blocksLeft)
	{
		int downloadedSoFar = blocksToDownload - blocksLeft;
		if (blocksToDownload == 0)
			onSynchronizationUpdate(10000);
		else
			onSynchronizationUpdate(10000*downloadedSoFar / blocksToDownload);
	}
	
	public void onChainDownloadStarted(Peer peer, int blocksLeft)
	{
		blocksToDownload = blocksLeft;
		if (blocksToDownload == 0)
			onSynchronizationUpdate(10000);
		else
			onSynchronizationUpdate(0);
	}
	
	public void onPeerConnected(Peer peer, int peerCount)
	{
		onPeerCountChange(peerCount);
	}
	
	
	public void onPeerDisconnected(Peer peer, int peerCount)
	{
		onPeerCountChange(peerCount);
	}
	
	public Message onPreMessageReceived(Peer peer, Message m)
	{
		return m;
	}
	
	public void onTransaction(Peer peer, Transaction t)
	{
		
	}
	
	public List<Message> getData(Peer peer, GetDataMessage m)
	{
		return null;
	}
}
