package pt.inesc.termite.msgsender;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.UnknownHostException;

import pt.inesc.termite.wifidirect.SimWifiP2pBroadcast;
import pt.inesc.termite.wifidirect.SimWifiP2pDevice;
import pt.inesc.termite.wifidirect.SimWifiP2pInfo;
import pt.inesc.termite.wifidirect.SimWifiP2pManager;
import pt.inesc.termite.wifidirect.SimWifiP2pManager.Channel;
import pt.inesc.termite.wifidirect.service.SimWifiP2pService;
import pt.inesc.termite.wifidirect.sockets.SimWifiP2pSocket;
import pt.inesc.termite.wifidirect.sockets.SimWifiP2pSocketManager;
import pt.inesc.termite.wifidirect.sockets.SimWifiP2pSocketServer;
import pt.inesc.termite.wifidirect.SimWifiP2pDeviceList;
import pt.inesc.termite.wifidirect.SimWifiP2pManager.PeerListListener;
import pt.inesc.termite.wifidirect.SimWifiP2pManager.GroupInfoListener;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;

import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Messenger;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;
import android.widget.Toast;

public class MsgSenderActivity extends Activity implements PeerListListener, GroupInfoListener {

	public static final String TAG = "msgsender";

	private SimWifiP2pManager mManager = null;
	private Channel mChannel = null;
	private Messenger mService = null;
	private boolean mBound = false;
	private SimWifiP2pSocketServer mSrvSocket = null;
	private SimWifiP2pSocket mCliSocket = null;
	private TextView mTextInput;
	private TextView mTextOutput;
	private SimWifiP2pBroadcastReceiver mReceiver;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// initialize the UI
		setContentView(R.layout.main);
		guiSetButtonListeners();
		guiUpdateInitState();

		// initialize the WDSim API
		SimWifiP2pSocketManager.Init(getApplicationContext());

		// register broadcast receiver
		IntentFilter filter = new IntentFilter();
		filter.addAction(SimWifiP2pBroadcast.WIFI_P2P_STATE_CHANGED_ACTION);
		filter.addAction(SimWifiP2pBroadcast.WIFI_P2P_PEERS_CHANGED_ACTION);
		filter.addAction(SimWifiP2pBroadcast.WIFI_P2P_NETWORK_MEMBERSHIP_CHANGED_ACTION);
		filter.addAction(SimWifiP2pBroadcast.WIFI_P2P_GROUP_OWNERSHIP_CHANGED_ACTION);
		mReceiver = new SimWifiP2pBroadcastReceiver(this);
		registerReceiver(mReceiver, filter);
	}

	@Override
	public void onPause() {
		super.onPause();
		unregisterReceiver(mReceiver);
	}

	/*
	 * Listeners associated to buttons
	 */

	private OnClickListener listenerWifiOnButton = new OnClickListener() {
		public void onClick(View v) {
			Intent intent = new Intent(v.getContext(), SimWifiP2pService.class);
			bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
			mBound = true;

			// spawn the chat server background task
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
				new IncomingCommTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
			}

			guiUpdateDisconnectedState();
		}
	};

	private OnClickListener listenerWifiOffButton = new OnClickListener() {
		public void onClick(View v) {
			if (mBound) {
				unbindService(mConnection);
				mBound = false;
				guiUpdateInitState();
			}
		}
	};

	private OnClickListener listenerInRangeButton = new OnClickListener() {
		public void onClick(View v) {
			if (mBound) {
				mManager.requestPeers(mChannel, MsgSenderActivity.this);
			} else {
				Toast.makeText(v.getContext(), "Service not bound", Toast.LENGTH_SHORT).show();
			}
		}
	};

	private OnClickListener listenerInGroupButton = new OnClickListener() {
		public void onClick(View v) {
			if (mBound) {
				mManager.requestGroupInfo(mChannel, MsgSenderActivity.this);
			} else {
				Toast.makeText(v.getContext(), "Service not bound", Toast.LENGTH_SHORT).show();
			}
		}
	};

	private OnClickListener listenerConnectButton = new OnClickListener() {
		@Override
		public void onClick(View v) {
			findViewById(R.id.idConnectButton).setEnabled(false);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
				new OutgoingCommTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, mTextInput.getText().toString());
			}
		}
	};

	private OnClickListener listenerSendButton = new OnClickListener() {
		@Override
		public void onClick(View v) {
			findViewById(R.id.idSendButton).setEnabled(false);

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
				new SendCommTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, mTextInput.getText().toString());
			}
		}
	};

	private OnClickListener listenerDisconnectButton = new OnClickListener() {
		@Override
		public void onClick(View v) {
			findViewById(R.id.idDisconnectButton).setEnabled(false);
			if (mCliSocket != null) {
				try {
					mCliSocket.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			mCliSocket = null;
			guiUpdateDisconnectedState();
		}
	};

	private ServiceConnection mConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			mService = new Messenger(service);
			mManager = new SimWifiP2pManager(mService);
			mChannel = mManager.initialize(getApplication(), getMainLooper(), null);
			mBound = true;
		}

		@Override
		public void onServiceDisconnected(ComponentName arg0) {
			mService = null;
			mManager = null;
			mChannel = null;
			mBound = false;
		}
	};

	/*
	 * Asynctasks implementing message exchange
	 */

	public class IncomingCommTask extends AsyncTask<Void, String, Void> {

		@Override
		protected Void doInBackground(Void... params) {
			try {
				mSrvSocket = new SimWifiP2pSocketServer(Integer.parseInt(getString(R.string.port)));
			} catch (IOException e) {
				e.printStackTrace();
			}
			while (!Thread.currentThread().isInterrupted()) {
				try {
					SimWifiP2pSocket sock = mSrvSocket.accept();
					try (BufferedReader sockIn = new BufferedReader(new InputStreamReader(sock.getInputStream()))) {
						String message;
						while ((message = sockIn.readLine()) != null) {
							publishProgress(message);
							sock.getOutputStream().write("ACK\n".getBytes()); // Envia confirmação (opcional)
							sock.getOutputStream().flush();
						}
					} catch (IOException e) {
						Log.d("Error reading socket:", e.getMessage());
					} finally {
						sock.close();
					}
				} catch (IOException e) {
					Log.d("Error socket:", e.getMessage());
					break;
				}
			}
			return null;
		}

		@Override
		protected void onProgressUpdate(String... values) {
			mTextOutput.append(values[0] + "\n");
		}
	}
	public class OutgoingCommTask extends AsyncTask<String, Void, String> {

		@Override
		protected void onPreExecute() {
			mTextOutput.setText("Connecting...");
		}

		@Override
		protected String doInBackground(String... params) {
			try {
				mCliSocket = new SimWifiP2pSocket(params[0], Integer.parseInt(getString(R.string.port)));
			} catch (UnknownHostException e) {
				return "Unknown Host:" + e.getMessage();
			} catch (IOException e) {
				return "IO error:" + e.getMessage();
			}
			return null;
		}

		@Override
		protected void onPostExecute(String result) {
			if (result != null) {
				guiUpdateDisconnectedState();
				mTextOutput.setText(result);
			} else {
				findViewById(R.id.idDisconnectButton).setEnabled(true);
				findViewById(R.id.idConnectButton).setEnabled(false);
				findViewById(R.id.idSendButton).setEnabled(true);
				mTextInput.setHint("");
				mTextInput.setText("");
				mTextOutput.setText("");
			}
		}
	}

	public class SendCommTask extends AsyncTask<String, String, Void> {

		@Override
		protected Void doInBackground(String... msg) {
			try {
				// Escreve a mensagem no OutputStream do socket
				mCliSocket.getOutputStream().write((msg[0] + "\n").getBytes());
				mCliSocket.getOutputStream().flush(); // Garante envio imediato

				// Lê a resposta (se necessário)
				BufferedReader sockIn = new BufferedReader(new InputStreamReader(mCliSocket.getInputStream()));
				String response = sockIn.readLine();
				if (response != null) {
					publishProgress(response);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			return null;
		}

		@Override
		protected void onProgressUpdate(String... values) {
			// Exibe a resposta recebida do servidor
			mTextOutput.append("Response: " + values[0] + "\n");
		}

		@Override
		protected void onPostExecute(Void result) {
			guiUpdateConnectedState();
		}
	}

	/*
	 * Listeners associated to Termite
	 */

	@Override
	public void onPeersAvailable(SimWifiP2pDeviceList peers) {
		StringBuilder peersStr = new StringBuilder();

		// compile list of devices in range
		for (SimWifiP2pDevice device : peers.getDeviceList()) {
			String devstr = "" + device.deviceName + " (" + device.getVirtIp() + ")\n";
			peersStr.append(devstr);
		}

		// display list of devices in range
		new AlertDialog.Builder(this)
				.setTitle("Devices in WiFi Range")
				.setMessage(peersStr.toString())
				.setNeutralButton("Dismiss", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) { }
				})
				.show();
	}

	@Override
	public void onGroupInfoAvailable(SimWifiP2pDeviceList devices, SimWifiP2pInfo groupInfo) {
		// compile list of network members
		StringBuilder peersStr = new StringBuilder();
		for (String deviceName : groupInfo.getDevicesInNetwork()) {
			SimWifiP2pDevice device = devices.getByName(deviceName);
			String devstr = "" + deviceName + " (" +
					((device == null)?"??":device.getVirtIp()) + ")\n";
			peersStr.append(devstr);
		}

		// display list of network members
		new AlertDialog.Builder(this)
				.setTitle("Devices in WiFi Network")
				.setMessage(peersStr.toString())
				.setNeutralButton("Dismiss", new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) { }
				})
				.show();
	}

	/*
	 * Helper methods for updating the interface
	 */

	private void guiSetButtonListeners() {
		findViewById(R.id.idConnectButton).setOnClickListener(listenerConnectButton);
		findViewById(R.id.idDisconnectButton).setOnClickListener(listenerDisconnectButton);
		findViewById(R.id.idSendButton).setOnClickListener(listenerSendButton);
		findViewById(R.id.idWifiOnButton).setOnClickListener(listenerWifiOnButton);
		findViewById(R.id.idWifiOffButton).setOnClickListener(listenerWifiOffButton);
		findViewById(R.id.idInRangeButton).setOnClickListener(listenerInRangeButton);
		findViewById(R.id.idInGroupButton).setOnClickListener(listenerInGroupButton);
	}

	private void guiUpdateInitState() {
		mTextInput = (TextView) findViewById(R.id.editText1);
		mTextInput.setHint("Type remote virtual IP (192.168.0.0/16)");
		mTextInput.setEnabled(false);

		mTextOutput = (TextView) findViewById(R.id.editText2);
		mTextOutput.setEnabled(false);
		mTextOutput.setText("");

		findViewById(R.id.idConnectButton).setEnabled(false);
		findViewById(R.id.idDisconnectButton).setEnabled(false);
		findViewById(R.id.idSendButton).setEnabled(false);
		findViewById(R.id.idWifiOnButton).setEnabled(true);
		findViewById(R.id.idWifiOffButton).setEnabled(false);
		findViewById(R.id.idInRangeButton).setEnabled(false);
		findViewById(R.id.idInGroupButton).setEnabled(false);
	}

	private void guiUpdateDisconnectedState() {
		mTextInput.setEnabled(true);
		mTextInput.setHint("Type remote virtual IP (192.168.0.0/16)");
		mTextOutput.setEnabled(true);
		mTextOutput.setText("");

		findViewById(R.id.idSendButton).setEnabled(false);
		findViewById(R.id.idConnectButton).setEnabled(true);
		findViewById(R.id.idDisconnectButton).setEnabled(false);
		findViewById(R.id.idWifiOnButton).setEnabled(false);
		findViewById(R.id.idWifiOffButton).setEnabled(true);
		findViewById(R.id.idInRangeButton).setEnabled(true);
		findViewById(R.id.idInGroupButton).setEnabled(true);
	}

	private void guiUpdateConnectedState() {
		mTextInput.setEnabled(true);
		mTextInput.setHint("Type your message here");
		mTextOutput.setEnabled(true);

		findViewById(R.id.idSendButton).setEnabled(true);
		findViewById(R.id.idConnectButton).setEnabled(false);
		findViewById(R.id.idDisconnectButton).setEnabled(true);
		findViewById(R.id.idWifiOnButton).setEnabled(false);
		findViewById(R.id.idWifiOffButton).setEnabled(false);
		findViewById(R.id.idInRangeButton).setEnabled(false);
		findViewById(R.id.idInGroupButton).setEnabled(false);
	}
}