/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.waveprotocol.box.server.waveserver;

import org.waveprotocol.box.common.ExceptionalIterator;
import org.waveprotocol.box.common.ThrowableReceiver;
import org.waveprotocol.box.server.waveletstate.WaveletStateException;
import org.waveprotocol.box.server.waveletstate.IndexingInProcessException;
import org.waveprotocol.box.server.serialize.OperationSerializer;
import org.waveprotocol.box.server.executor.ExecutorAnnotations;
import org.waveprotocol.box.server.persistence.deltas.WaveletDeltaRecord;
import org.waveprotocol.box.server.persistence.blocks.Interval;
import org.waveprotocol.box.server.persistence.blocks.VersionRange;
import org.waveprotocol.box.stat.Timed;
import org.waveprotocol.box.common.Receiver;
import org.waveprotocol.wave.clientserver.ReturnCode;
import org.waveprotocol.wave.crypto.SignatureException;
import org.waveprotocol.wave.crypto.SignerInfo;
import org.waveprotocol.wave.crypto.UnknownSignerException;
import org.waveprotocol.wave.federation.FederationErrorProto.FederationError;
import org.waveprotocol.wave.federation.FederationErrors;
import org.waveprotocol.wave.federation.FederationException;
import org.waveprotocol.wave.federation.FederationRemoteBridge;
import org.waveprotocol.wave.federation.Proto.ProtocolHashedVersion;
import org.waveprotocol.wave.federation.Proto.ProtocolSignature;
import org.waveprotocol.wave.federation.Proto.ProtocolSignedDelta;
import org.waveprotocol.wave.federation.Proto.ProtocolSignerInfo;
import org.waveprotocol.wave.federation.Proto.ProtocolWaveletDelta;
import org.waveprotocol.wave.federation.WaveletFederationListener;
import org.waveprotocol.wave.federation.WaveletFederationProvider;
import org.waveprotocol.wave.model.id.IdUtil;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.id.WaveletName;
import org.waveprotocol.wave.model.id.SegmentId;
import org.waveprotocol.wave.model.operation.OperationException;
import org.waveprotocol.wave.model.operation.wave.TransformedWaveletDelta;
import org.waveprotocol.wave.model.version.HashedVersion;
import org.waveprotocol.wave.model.wave.InvalidParticipantAddress;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.data.ReadableWaveletData;
import org.waveprotocol.wave.model.util.Pair;
import org.waveprotocol.wave.util.logging.Log;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The main class that services the FederationHost, FederationRemote and ClientFrontend.
 */
@Singleton
public class WaveServerImpl implements WaveletProvider, WaveletFederationProvider, WaveletFederationListener.Factory {

  private static final Log LOG = Log.get(WaveServerImpl.class);

  private final Executor listenerExecutor;
  private final CertificateManager certificateManager;
  private final WaveletFederationProvider federationRemote;
  private final WaveMap waveMap;
  private boolean initialized = false;

  //
  // WaveletFederationListener.Factory implementation.
  //

  /**
   * Listener for notifications coming from the Federation Remote. For now we accept updates
   * for wavelets on any domain.
   */
  @Override
  public WaveletFederationListener listenerForDomain(final String domain) {
    return new WaveletFederationListener() {
      @Override
      public void waveletDeltaUpdate(final WaveletName waveletName,
          List<ByteString> deltas, final WaveletUpdateCallback callback) {
        Preconditions.checkArgument(!deltas.isEmpty());

        if (isLocalWavelet(waveletName)) {
          LOG.warning("Remote tried to update local wavelet " + waveletName);
          callback.onFailure(FederationErrors.badRequest("Received update to local wavelet"));
          return;
        }

        // Update wavelet container with the applied deltas
        final RemoteWaveletContainer remoteWavelet;
        try {
          remoteWavelet = getOrCreateRemoteWavelet(waveletName);
        } catch (WaveletStateException ex) {
          LOG.severe("Can't get local wavelet " + waveletName, ex);
          callback.onFailure(FederationErrors.internalServerError("Can't get local wavelet"));
          return;
        }

        // Update this remote wavelet with the immediately incoming delta,
        // providing a callback so that incoming historic deltas (as well as
        // this delta) can be provided to the wave bus.
        final ListenableFuture<Void> result =
            remoteWavelet.update(deltas, domain, federationRemote, certificateManager);
        result.addListener(
            new Runnable() {
              @Override
              public void run() {
                try {
                  FutureUtil.getResultOrPropagateException(result, FederationException.class);
                  callback.onSuccess();
                } catch (FederationException e) {
                  LOG.warning("Failed updating " + waveletName, e);
                  callback.onFailure(e.getError());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  LOG.severe("Interrupted updating " + waveletName, e);
                  callback.onFailure(FederationErrors.internalServerError("Interrupted"));
                }
              }
            },
            listenerExecutor);
      }

      @Override
      public void waveletCommitUpdate(WaveletName waveletName,
          ProtocolHashedVersion committedVersion, WaveletUpdateCallback callback) {
        Preconditions.checkNotNull(committedVersion);

        if (isLocalWavelet(waveletName)) {
          LOG.warning("Got commit update for local wavelet " + waveletName);
          callback.onFailure(FederationErrors.badRequest("Received comit update to local wavelet"));
          return;
        }

        RemoteWaveletContainer wavelet;
        try {
          wavelet = getRemoteWavelet(waveletName);
        } catch (WaveletStateException e) {
          LOG.warning("Failed to access wavelet " + waveletName + " for commit update", e);
          callback.onFailure(FederationErrors.internalServerError("Storage access failure"));
          return;
        }
        if (wavelet != null) {
          try {
            wavelet.commit(OperationSerializer.deserialize(committedVersion));
          } catch (WaveletStateException e) {
            LOG.warning("Failed to access wavelet " + waveletName + " for commit update", e);
            callback.onFailure(FederationErrors.internalServerError("Storage access failure"));
            return;
          }
        } else {
          // TODO(soren): This should really be changed to create the wavelet if it doesn't
          // already exist and go get history up committedVersion. Moreover, when the
          // protocol is enhanced to deliver commit updates reliably, we will probably need
          // to only return success when we successfully retrieved history and persisted it all.
         LOG.info("Got commit update for missing wavelet " + waveletName);
        }
        callback.onSuccess();
      }
    };
  }

  //
  // WaveletFederationProvider implementation.
  //

  @Timed
  @Override
  public void submitRequest(WaveletName waveletName, ProtocolSignedDelta signedDelta,
      SubmitResultCallback listener) {
    if (!isLocalWavelet(waveletName)) {
      LOG.warning("Remote tried to submit to non-local wavelet " + waveletName);
      listener.onFailure(FederationErrors.badRequest("Non-local wavelet update"));
      return;
    }

    ProtocolWaveletDelta delta;
    try {
      delta = ByteStringMessage.parseProtocolWaveletDelta(signedDelta.getDelta()).getMessage();
    } catch (InvalidProtocolBufferException e) {
      LOG.warning("Submit request: Invalid delta protobuf. WaveletName: " + waveletName, e);
      listener.onFailure(FederationErrors.badRequest("Signed delta contains invalid delta"));
      return;
    }

    // Disallow creation of wavelets by remote users.
    if (delta.getHashedVersion().getVersion() == 0) {
      LOG.warning("Remote user tried to submit delta at version 0 - disallowed. " + signedDelta);
      listener.onFailure(FederationErrors.badRequest("Remote users may not create wavelets."));
      return;
    }

    try {
      certificateManager.verifyDelta(signedDelta);
      submitDelta(waveletName, delta, signedDelta, listener);
    } catch (SignatureException e) {
      LOG.warning("Submit request: Delta failed verification. WaveletName: " + waveletName +
          " delta: " + signedDelta, e);
      listener.onFailure(FederationErrors.badRequest("Remote verification failed"));
    } catch (UnknownSignerException e) {
      LOG.warning("Submit request: unknown signer.  WaveletName: " + waveletName +
          "delta: " + signedDelta, e);
      listener.onFailure(FederationErrors.internalServerError("Unknown signer"));
    }
  }

  @Timed
  @Override
  public void requestHistory(WaveletName waveletName, String domain,
      ProtocolHashedVersion startVersion, ProtocolHashedVersion endVersion,
      final long lengthLimit, HistoryResponseListener listener) {
    LocalWaveletContainer wavelet = loadLocalWavelet(waveletName, listener);
    if (wavelet != null) {
      final ImmutableList.Builder<ByteString> deltaHistoryBytes = ImmutableList.builder();
      final AtomicInteger length = new AtomicInteger(0);
      try {
        wavelet.requestDeltaHistory(OperationSerializer.deserialize(startVersion),
            OperationSerializer.deserialize(endVersion),
            new ThrowableReceiver<WaveletDeltaRecord, WaveletStateException>() {

          @Override
          public boolean put(WaveletDeltaRecord delta) {
            ByteString bytes = delta.getAppliedDelta().getByteString();
            deltaHistoryBytes.add(bytes);
            if (length.addAndGet(bytes.size()) >= lengthLimit) {
              return false;
            }
            return true;
          }
        });
      } catch (WaveServerException e) {
        LOG.severe("Error retrieving wavelet history: " + waveletName + " " + startVersion +
            " - " + endVersion);
        // TODO(soren): choose a better error code (depending on e)
        listener.onFailure(FederationErrors.badRequest(
            "Server error while retrieving wavelet history."));
        return;
      }

      // Now determine whether we received the entire requested wavelet history.
      LOG.info("Found deltaHistory between " + startVersion + " - " + endVersion
          + ", returning to requester domain " + domain);
      listener.onSuccess(deltaHistoryBytes.build(), endVersion, endVersion.getVersion());
    }
  }

  @Timed
  @Override
  public void getDeltaSignerInfo(ByteString signerId,
      WaveletName waveletName, ProtocolHashedVersion deltaEndVersion,
      DeltaSignerInfoResponseListener listener) {
    LocalWaveletContainer wavelet = loadLocalWavelet(waveletName, listener);
    if (wavelet != null) {
      HashedVersion endVersion = OperationSerializer.deserialize(deltaEndVersion);
      try {
        if (wavelet.isDeltaSigner(endVersion, signerId)) {
          ProtocolSignerInfo signerInfo = certificateManager.retrieveSignerInfo(signerId);
          if (signerInfo == null) {
            // Oh no!  We are supposed to store it, and we already know they did sign this delta.
            LOG.severe("No stored signer info for valid getDeltaSignerInfo on " + waveletName);
            listener.onFailure(FederationErrors.badRequest("Unknown signer info"));
          } else {
            listener.onSuccess(signerInfo);
          }
        } else {
          LOG.info("getDeltaSignerInfo was not authrorised for wavelet " + waveletName
            + ", end version " + deltaEndVersion);
          listener.onFailure(FederationErrors.badRequest("Not authorised to get signer info"));
        }
      } catch (WaveletStateException ex) {
        listener.onFailure(FederationErrors.internalServerError("Wavelet access error"));
      }
    }
  }

  @Override
  public void postSignerInfo(String destinationDomain, ProtocolSignerInfo signerInfo,
      PostSignerInfoResponseListener listener) {
    try {
      certificateManager.storeSignerInfo(signerInfo);
    } catch (SignatureException e) {
      String error = "verification failure from domain " + signerInfo.getDomain();
      LOG.warning("incoming postSignerInfo: " + error, e);
      listener.onFailure(FederationErrors.badRequest(error));
      return;
    }
    listener.onSuccess();
  }

  //
  // WaveletProvider implementation.
  //

  @Override
  public void initialize() throws WaveServerException {
    Preconditions.checkState(!initialized, "Wave server already initialized");
    initialized = true;
  }

  @Override
  public void getDeltaHistory(WaveletName waveletName, HashedVersion versionStart, HashedVersion versionEnd,
      final ThrowableReceiver<WaveletDeltaRecord, WaveServerException> receiver) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      throw new AccessControlException(
          "Client request for history made for non-existent wavelet: " + waveletName);
    }
    wavelet.requestDeltaHistory(versionStart, versionEnd, new ThrowableReceiver<WaveletDeltaRecord, WaveletStateException>() {

      @Override
      public boolean put(WaveletDeltaRecord record) throws WaveletStateException {
        try {
          return receiver.put(record);
        } catch (WaveServerException ex) {
          throw new WaveletStateException(ex);
        }
      }
    });
  }

  @Timed
  @Override
  public HashedVersion getNearestHashedVersion(WaveletName waveletName, long version)
      throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      throw new AccessControlException(
          "Client request for nearest less version made for non-existent wavelet: " + waveletName);
    }
    return wavelet.getNearestHashedVersion(version);
  }

  @Timed
  @Override
  public
  ExceptionalIterator<WaveId, WaveServerException> getWaveIds() {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    return waveMap.getWaveIds();
  }

  @Timed
  @Override
  public
  ImmutableSet<WaveletId> getWaveletIds(WaveId waveId) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    return waveMap.getWaveletIds(waveId);
  }

  @Override
  public HashedVersion getLastModifiedVersion(WaveletName waveletName) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client requested version for non-existent wavelet: " + waveletName);
      return null;
    } else {
      return wavelet.getLastModifiedVersion();
    }
  }

  @Override
  public Pair<HashedVersion, Long> getLastModifiedVersionAndTime(WaveletName waveletName) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client requested version for non-existent wavelet: " + waveletName);
      return null;
    } else {
      return wavelet.getLastModifiedVersionAndTime();
    }
  }

  @Timed
  @Override
  public HashedVersion getLastCommittedVersion(WaveletName waveletName) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client requested version for non-existent wavelet: " + waveletName);
      return null;
    } else {
      return wavelet.getLastCommittedVersion();
    }
  }

  @Override
  public ParticipantId getCreator(WaveletName waveletName) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client requested creator for non-existent wavelet: " + waveletName);
      return null;
    } else {
      return wavelet.getCreator();
    }
  }

  @Override
  public long getCreationTime(WaveletName waveletName) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client requested creation time for non-existent wavelet: " + waveletName);
      return 0;
    } else {
      return wavelet.getCreationTime();
    }
  }

  @Override
  public boolean hasParticipant(WaveletName waveletName, ParticipantId participant) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client checked participant existance for non-existent wavelet: " + waveletName);
      return false;
    } else {
      return wavelet.hasParticipant(participant);
    }
  }

  @Override
  public Set<ParticipantId> getParticipants(WaveletName waveletName) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client requested participants for non-existent wavelet: " + waveletName);
      return null;
    } else {
      return wavelet.getParticipants();
    }
  }

  @Override
  public Set<ParticipantId> getParticipants(WaveletName waveletName, long version) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client requested participants for non-existent wavelet: " + waveletName);
      return null;
    } else {
      return wavelet.getParticipants(version);
    }
  }

  @Override
  public ReadableWaveletData getSnapshot(WaveletName waveletName) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client requested snapshot for non-existent wavelet: " + waveletName);
      return null;
    } else {
      return wavelet.getSnapshot();
    }
  }

  @Timed
  @Override
  public ReadableWaveletData getSnapshot(WaveletName waveletName, HashedVersion version) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client requested snapshot for non-existent wavelet: " + waveletName);
      return null;
    } else {
      return wavelet.getSnapshot(version);
    }
  }

  @Override
  public Set<SegmentId> getSegmentIds(WaveletName waveletName, long version) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client requested snapshot for non-existent wavelet: " + waveletName);
      return Collections.EMPTY_SET;
    } else {
      return wavelet.getSegmentIds(version);
    }
  }

  @Override
  public void getIntervals(WaveletName waveletName, Map<SegmentId, VersionRange> ranges, boolean onlyFromCache,
      Receiver<Pair<SegmentId, Interval>> receiver) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.info("client requested snapshot for non-existent wavelet: " + waveletName);
    } else {
      wavelet.getIntervals(ranges, onlyFromCache, receiver);
    }
  }

  @Timed
  @Override
  public void remakeIndex(WaveletName waveletName) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    if (wavelet == null) {
      LOG.warning("Wavelet " + waveletName + " is not exists.");
    } else {
      wavelet.remakeIndex();
    }
  }

  @Timed
  @Override
  public void openRequest(WaveletName waveletName, List<HashedVersion> knownVersions,
      ParticipantId participantId, final OpenRequestCallback callback) {
    if (isLocalWavelet(waveletName)) {
      LOG.info("Connect to " + waveletName + " by " + participantId);

      try {
        LocalWaveletContainer wavelet;
        // Prevents open of existing wave as new.
        if (IdUtil.isConversationalId(waveletName.waveletId)) {
          wavelet = getLocalWavelet(waveletName);
          boolean create = knownVersions.size() == 1 && knownVersions.get(0).getVersion() == 0;
          if (create) {
            if (wavelet != null) {
              callback.onFailure(ReturnCode.ALREADY_EXISTS, waveletName.toString() + " already exists.");
              return;
            }
            wavelet = getOrCreateLocalWavelet(waveletName);
          } else if (wavelet == null) {
            callback.onFailure(ReturnCode.NOT_EXISTS, waveletName.toString() + " not exists.");
            return;
          }
        } else {
          wavelet = getOrCreateLocalWavelet(waveletName);
        }
        if (!wavelet.checkAccessPermission(participantId)) {
          callback.onFailure(ReturnCode.NOT_AUTHORIZED,
              participantId + " is not a participant of " + waveletName);
          return;
        }
        callback.onSuccess(wavelet.getConnectVersion(knownVersions), wavelet.getLastCommittedVersion());
      } catch (IndexingInProcessException ex) {
        LOG.warning("Indexing in process for wavelet " + waveletName + 
          ", total " + ex.getTargetVersion() + ", indexed " + ex.getCurrentVersion());
        callback.onFailure(ReturnCode.INDEXING_IN_PROCESS, ex.getTargetVersion() + " " + ex.getCurrentVersion());
      } catch (WaveServerException ex) {
        LOG.severe("Open of " + waveletName.toString() + " error", ex);
        callback.onFailure(ReturnCode.INTERNAL_ERROR, ex.getMessage());
        return;
      }
    } else {
      throw new UnsupportedOperationException("Connect request to remote wavelet not supported yet.");
    }
  }

  @Timed
  @Override
  public void submitRequest(WaveletName waveletName, ProtocolWaveletDelta delta, final SubmitRequestCallback callback) {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    if (delta.getOperationCount() == 0) {
      callback.onFailure(ReturnCode.BAD_REQUEST,
          "Empty delta at version " + delta.getHashedVersion().getVersion());
      return;
    }

    // The serialised version of this delta happens now.  This should be the only place, ever!
    ProtocolSignedDelta signedDelta =
        certificateManager.signDelta(ByteStringMessage.serializeMessage(delta));

    submitDelta(waveletName, delta, signedDelta, new SubmitResultCallback() {
      @Override
      public void onFailure(FederationError errorMessage) {
        callback.onFailure(ReturnCode.INTERNAL_ERROR, errorMessage.getErrorMessage());
      }

      @Override
      public void onSuccess(int operationsApplied,
          ProtocolHashedVersion hashedVersionAfterApplication, long applicationTimestamp) {
        callback.onSuccess(operationsApplied,
            OperationSerializer.deserialize(hashedVersionAfterApplication),
            applicationTimestamp);
      }
    });
  }

  @Override
  public boolean checkExistence(WaveletName waveletName) throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    return getWavelet(waveletName) != null;
  }

  @Override
  public boolean checkAccessPermission(WaveletName waveletName, ParticipantId participantId)
      throws WaveServerException {
    Preconditions.checkState(initialized, "Wave server not yet initialized");
    WaveletContainer wavelet = getWavelet(waveletName);
    return wavelet != null && wavelet.checkAccessPermission(participantId);
  }

  /**
   * Constructor.
   *
   * @param listenerExecutor executes callback listeners
   * @param certificateManager provider of certificates; it also determines which
   *        domains this wave server regards as local wavelets.
   * @param federationRemote federation remote interface
   * @param waveMap records the waves and wavelets in memory
   */
  @Inject
  WaveServerImpl(@ExecutorAnnotations.ListenerExecutor Executor listenerExecutor,
      CertificateManager certificateManager,
      @FederationRemoteBridge WaveletFederationProvider federationRemote, WaveMap waveMap) {
    this.listenerExecutor = listenerExecutor;
    this.certificateManager = certificateManager;
    this.federationRemote = federationRemote;
    this.waveMap = waveMap;

    LOG.info("Wave Server configured to host local domains: "
        + certificateManager.getLocalDomains());

    // Preemptively add our own signer info to the certificate manager
    SignerInfo signerInfo = certificateManager.getLocalSigner().getSignerInfo();
    if (signerInfo != null) {
      try {
        certificateManager.storeSignerInfo(signerInfo.toProtoBuf());
      } catch (SignatureException e) {
        LOG.severe("Failed to add our own signer info to the certificate store", e);
      }
    }
  }

  /**
   * Loads a local wavelet. If the request is invalid then the listener is
   * notified and null returned.
   *
   * @param waveletName wavelet to load
   * @param listener listener to notify on failure
   * @return the wavelet container, or null on failure
   */
  LocalWaveletContainer loadLocalWavelet(WaveletName waveletName,
      FederationListener listener) {
    // TODO(soren): once we support federated groups, expand support to request remote wavelets too.
    if (!isLocalWavelet(waveletName)) {
      LOG.warning("Attempt to get delta signer info for remote wavelet " + waveletName);
      listener.onFailure(FederationErrors.badRequest("Wavelet not hosted here."));
      return null;
    }

    LocalWaveletContainer wavelet;
    try {
      wavelet = getLocalWavelet(waveletName);
    } catch (WaveletStateException e) {
      LOG.warning("Failed to access wavelet " + waveletName, e);
      listener.onFailure(FederationErrors.internalServerError("Storage access failure"));
      return null;
    }
    if (wavelet == null) {
      LOG.info("Non-existent wavelet " + waveletName);
      // TODO(soren): determine if it's ok to leak the fact that the wavelet doesn't exist,
      // or if we should hide this information and return the same error code as for wrong
      // version number or history hash
      listener.onFailure(FederationErrors.badRequest("Wavelet does not exist"));
    }
    return wavelet;
  }

  private boolean isLocalWavelet(WaveletName waveletName) {
    boolean isLocal = getLocalDomains().contains(waveletName.waveletId.getDomain());
    LOG.fine("" + waveletName + " is " + (isLocal? "" : "not") + " local");
    return isLocal;
  }

  private Set<String> getLocalDomains() {
    return certificateManager.getLocalDomains();
  }

  /**
   * Returns a container for a remote wavelet. If it doesn't exist, it will be created.
   * This method is only called in response to a Federation Remote doing an update
   * or commit on this wavelet.
   *
   * @param waveletName name of wavelet
   * @return an existing or new instance.
   * @throws IllegalArgumentException if the name refers to a local wavelet.
   */
  RemoteWaveletContainer getOrCreateRemoteWavelet(WaveletName waveletName) throws WaveletStateException {
    Preconditions.checkArgument(!isLocalWavelet(waveletName), "%s is not remote", waveletName);
    return waveMap.getOrCreateRemoteWavelet(waveletName);
  }

  /**
   * Returns a container for a local wavelet. If it doesn't exist, it will be created.
   *
   * @param waveletName name of wavelet
   * @return an existing or new instance.
   * @throws IllegalArgumentException if the name refers to a remote wavelet.
   */
  LocalWaveletContainer getOrCreateLocalWavelet(WaveletName waveletName) throws WaveletStateException {
    Preconditions.checkArgument(isLocalWavelet(waveletName), "%s is not local", waveletName);
    return waveMap.getOrCreateLocalWavelet(waveletName);
  }

  RemoteWaveletContainer getRemoteWavelet(WaveletName waveletName)
      throws WaveletStateException {
    Preconditions.checkArgument(!isLocalWavelet(waveletName), "%s is not remote", waveletName);
    return waveMap.getRemoteWavelet(waveletName);
  }

  LocalWaveletContainer getLocalWavelet(WaveletName waveletName)
      throws WaveletStateException {
    Preconditions.checkArgument(isLocalWavelet(waveletName), "%s is not local", waveletName);
    return waveMap.getLocalWavelet(waveletName);
  }

  /**
   * Returns a generic wavelet container, when the caller doesn't need to validate whether
   * its a local or remote wavelet.
   *
   * @param waveletName name of wavelet.
   * @return an wavelet container or null if it doesn't exist.
   * @throw WaveServerException if storage lookup fails
   */
  WaveletContainer getWavelet(WaveletName waveletName) throws WaveServerException {
    return isLocalWavelet(waveletName) ?
        waveMap.getLocalWavelet(waveletName) : waveMap.getRemoteWavelet(waveletName);
  }

  /**
   * Callback interface for sending a list of certificates to a domain.
   */
  private interface PostSignerInfoCallback {
    public void done(int successCount);
  }

  /**
   * Submit the delta to local or remote wavelets, return results via listener.
   * Also broadcast updates to federationHosts and clientFrontend.
   *
   * @param waveletName the wavelet to apply the delta to
   * @param delta the {@link ProtocolWaveletDelta} inside {@code signedDelta}
   * @param signedDelta the signed delta
   * @param resultCallback callback
   *
   * TODO: For now the WaveletFederationProvider will have to ensure this is a
   * local wavelet. Once we support federated groups, that test should be
   * removed.
   */
  @Timed
  void submitDelta(final WaveletName waveletName, final ProtocolWaveletDelta delta,
      final ProtocolSignedDelta signedDelta, final SubmitResultCallback resultCallback) {
    Preconditions.checkArgument(delta.getOperationCount() > 0, "empty delta");

    if (isLocalWavelet(waveletName)) {
      LOG.info("Submit to " + waveletName + " by " + delta.getAuthor() + " @ "
          + delta.getHashedVersion().getVersion() + " with " + delta.getOperationCount() + " ops");

      LocalWaveletContainer wavelet;
      try {
        wavelet = getOrCreateLocalWavelet(waveletName);
        if (wavelet == null) {
          resultCallback.onFailure(FederationErrors.badRequest("No such wavelet " + waveletName));
          return;
        }
        if (!wavelet.checkAccessPermission(ParticipantId.of(delta.getAuthor()))) {
          resultCallback.onFailure(FederationErrors.badRequest(
              delta.getAuthor() + " is not a participant of " + waveletName));
          return;
        }
      } catch (InvalidParticipantAddress e) {
        resultCallback.onFailure(FederationErrors.badRequest(
            "Invalid author address: " + e.getMessage()));
        return;
      } catch (WaveServerException e) {
        resultCallback.onFailure(FederationErrors.internalServerError(e.getMessage()));
        return;
      }

      try {
        if (LOG.isFinestLoggable()) {
          LOG.finest("Delta: " + delta);
        }

        WaveletDeltaRecord submitResult = wavelet.submitRequest(waveletName, signedDelta);
        TransformedWaveletDelta transformedDelta = submitResult.getTransformedDelta();

        if (LOG.isFinestLoggable()) {
          LOG.finest("Transforned delta: " + transformedDelta);
        } else if (LOG.isInfoLoggable()) {
          LOG.info("Submit result for " + waveletName + " by "
              + transformedDelta.getAuthor() + " applied "
              + transformedDelta.size() + " ops at v: "
              + transformedDelta.getAppliedAtVersion() + " t: "
              + transformedDelta.getApplicationTimestamp());
        }

        resultCallback.onSuccess(delta.getOperationCount(),
            OperationSerializer.serialize(transformedDelta.getResultingVersion()),
            transformedDelta.getApplicationTimestamp());
      } catch (OperationException | InvalidProtocolBufferException | InvalidHashException ex) {
        LOG.severe("Submit delta error", ex);
        resultCallback.onFailure(FederationErrors.badRequest(ex.getMessage()));
      } catch (Exception ex) {
        LOG.severe("Submit delta error", ex);
        resultCallback.onFailure(FederationErrors.internalServerError(ex.getMessage()));
      }
    } else {
      // For remote wavelets post required signatures to the authorative server then send delta
      postAllSignerInfo(signedDelta.getSignatureList(), waveletName.waveletId.getDomain(),
          new PostSignerInfoCallback() {
            @Override public void done(int successCount) {
              LOG.info("Remote: successfully sent " + successCount + " of "
                  + signedDelta.getSignatureCount() + " certs to "
                  + waveletName.waveletId.getDomain());
              federationRemote.submitRequest(waveletName, signedDelta, resultCallback);
            }
          });
    }
  }

  /**
   * Post a list of certificates to a domain and run a callback when all are finished.  The
   * callback will run whether or not all posts succeed.
   *
   * @param sigs list of signatures to post signer info for
   * @param domain to post signature to
   * @param callback to run when all signatures have been posted, successfully or unsuccessfully
   */
  private void postAllSignerInfo(final List<ProtocolSignature> sigs, final String domain,
      final PostSignerInfoCallback callback) {

    // In the current implementation there should only be a single signer
    if (sigs.size() != 1) {
      LOG.warning(sigs.size() + " signatures to broadcast, expecting exactly 1");
    }

    final AtomicInteger resultCount = new AtomicInteger(sigs.size());
    final AtomicInteger successCount = new AtomicInteger(0);

    for (final ProtocolSignature sig : sigs) {
      final ProtocolSignerInfo psi = certificateManager.retrieveSignerInfo(sig.getSignerId());

      if (psi == null) {
        LOG.warning("Couldn't find signer info for " + sig);
        if (resultCount.decrementAndGet() == 0) {
          LOG.info("Finished signature broadcast with " + successCount.get()
              + " successful, running callback");
          callback.done(successCount.get());
        }
      } else {
        federationRemote.postSignerInfo(domain, psi, new PostSignerInfoResponseListener() {
          @Override
          public void onFailure(FederationError error) {
            LOG.warning("Failed to post " + sig + " to " + domain + ": " + error);
            countDown();
          }

          @Override
          public void onSuccess() {
            LOG.info("Successfully broadcasted " + sig + " to " + domain);
            successCount.incrementAndGet();
            countDown();
          }

          private void countDown() {
            if (resultCount.decrementAndGet() == 0) {
              LOG.info("Finished signature broadcast with " + successCount.get()
                  + " successful, running callback");
              callback.done(successCount.get());
            }
          }
        });
      }
    }
  }
}
