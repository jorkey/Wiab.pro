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

package org.waveprotocol.wave.concurrencycontrol.wave;

import org.waveprotocol.wave.common.logging.PrintLogger;
import org.waveprotocol.wave.concurrencycontrol.channel.OperationChannelMultiplexerImpl;
import org.waveprotocol.wave.concurrencycontrol.channel.ViewChannelImpl;
import org.waveprotocol.wave.concurrencycontrol.channel.WaveViewService;
import org.waveprotocol.wave.concurrencycontrol.testing.MockWaveViewService;
import org.waveprotocol.wave.model.document.Document;
import org.waveprotocol.wave.model.document.operation.DocInitialization;
import org.waveprotocol.wave.model.document.operation.DocOp;
import org.waveprotocol.wave.model.id.IdGenerator;
import org.waveprotocol.wave.model.id.IdGeneratorImpl;
import org.waveprotocol.wave.model.id.WaveId;
import org.waveprotocol.wave.model.id.WaveletId;
import org.waveprotocol.wave.model.operation.SilentOperationSink;
import org.waveprotocol.wave.model.operation.wave.BasicWaveletOperationContextFactory;
import org.waveprotocol.wave.model.operation.wave.TransformedWaveletDelta;
import org.waveprotocol.wave.model.schema.SchemaCollection;
import org.waveprotocol.wave.model.schema.SchemaProvider;
import org.waveprotocol.wave.model.testing.DeltaTestUtil;
import org.waveprotocol.wave.model.testing.FakeHashedVersionFactory;
import org.waveprotocol.wave.model.util.ImmediateExcecutionScheduler;
import org.waveprotocol.wave.model.version.HashedVersion;
import org.waveprotocol.wave.model.wave.ObservableWavelet;
import org.waveprotocol.wave.model.wave.ParticipantId;
import org.waveprotocol.wave.model.wave.ParticipationHelper;
import org.waveprotocol.wave.model.wave.WaveViewListener;
import org.waveprotocol.wave.model.wave.data.impl.WaveletDataImpl;
import org.waveprotocol.wave.model.wave.opbased.WaveViewImpl.WaveletConfigurator;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import junit.framework.TestCase;
import org.waveprotocol.box.common.DeltaSequence;
import org.waveprotocol.wave.concurrencycontrol.channel.Accessibility;
import org.waveprotocol.wave.concurrencycontrol.channel.OperationChannelMultiplexer.KnownWavelet;
import org.waveprotocol.wave.clientserver.ReturnCode;
import org.waveprotocol.wave.clientserver.ReturnStatus;
import org.waveprotocol.wave.model.operation.OperationException;
import org.waveprotocol.wave.model.operation.OperationRuntimeException;
import org.waveprotocol.wave.model.testing.BasicFactories;
import org.waveprotocol.wave.model.wave.data.ObservableWaveletData;

/**
 * Tests the CcBasedWaveView class.
 *
 * @author zdwang@google.com (David Wang)
 */

public class CcBasedWaveViewTest extends TestCase {
  private static final WaveId WAVE_ID = WaveId.of("example.com", "waveId_1");
  private static final WaveletId GENERATED_WAVELET_ID = WaveletId.of("example.com", "some_id");
  private static final WaveletId ROOT_WAVELET_ID
      = new IdGeneratorImpl("example.com", null).newConversationRootWaveletId();
  private static final String GENERATED_BLIP_ID = "some blip id";
  private static final ParticipantId USER_ID = new ParticipantId("userId_1@example.com");
  private static final SchemaProvider SCHEMAS = SchemaCollection.empty();
  private static final String CHANNEL = "ch1";
  private static final long TIMESTAMP = 12345;
  private static final ObservableWaveletData.Factory<?> DATA_FACTORY =
      BasicFactories.waveletDataImplFactory();


  private static class MockCcDocument implements CcDocument {
    public enum MethodCall {
      FLUSH,
      AS_OPERATION,
      CONSUME
    }

    public class MethodCallContext {
      final MethodCall method;
      /** Saved argument to flush() call */
      final Runnable command;
      /** Saved argument to consume() call. */
      final DocOp op;

      public MethodCallContext(MethodCall method) {
        this(method, null, null);
      }

      public MethodCallContext(MethodCall method, Runnable command) {
        this(method, command, null);
      }

      public MethodCallContext(MethodCall method, DocOp op) {
        this(method, null, op);
      }
      public MethodCallContext(MethodCall method, Runnable command, DocOp op) {
        this.method = method;
        this.command = command;
        this.op = op;
      }

    }

    ArrayList<MethodCallContext> methodCalls = new ArrayList<MethodCallContext>();

    /** When flush() is called, the result we return */
    boolean flush_return = true;

    @Override
    public boolean flush(Runnable resume) {
      methodCalls.add(new MethodCallContext(MethodCall.FLUSH, resume));
      return flush_return;
    }

    @Override
    public DocInitialization asOperation() {
      methodCalls.add(new MethodCallContext(MethodCall.AS_OPERATION));
      return null;
    }

    @Override
    public void consume(DocOp op){
      methodCalls.add(new MethodCallContext(MethodCall.CONSUME, op));
    }

    public MethodCallContext expectedCall(MethodCall method) {
      assertEquals(method, methodCalls.get(0).method);
      return methodCalls.remove(0);
    }

    public void expectedNothing() {
      assertEquals(0, methodCalls.size());
    }

    @Override
    public Document getMutableDocument() {
      return null;
    }

    @Override
    public void init(SilentOperationSink<? super DocOp> sink) {
    }
  }

  private static class MockOpenListener implements CcBasedWaveView.OpenListener {

    private boolean opened = false;

    @Override
    public void onConnected() {
      opened = true;
    }

  }

  private final MockCcDocument doc = new MockCcDocument();

  private final CcBasedWaveViewImpl.CcDocumentFactory<?> docFactory =
      new CcBasedWaveViewImpl.CcDocumentFactory<CcDocument>() {
    @Override
    public CcDocument create(WaveletId waveletId, String blipId,
        DocInitialization content) {
      if (blipId.equals(GENERATED_BLIP_ID)) {
        return doc;
      } else {
        return new CcDataDocumentImpl(SCHEMAS.getSchemaForId(waveletId, blipId), content);
      }
    }

    @Override
    public CcDocument get(WaveletId waveletId, String blipId) {
      return doc;
    }
  };

  private class MockWaveViewListener implements WaveViewListener {
    ObservableWavelet addedWavelet = null;

    @Override
    public void onWaveletAdded(ObservableWavelet wavelet) {
      this.addedWavelet = wavelet;
    }

    @Override
    public void onWaveletRemoved(ObservableWavelet wavelet) {
      reset();
    }

    public void reset() {
      addedWavelet = null;
    }
  }

  private final IdGenerator idGenerator = new IdGenerator() {
    @Override
    public String newBlipId() {
      return GENERATED_BLIP_ID;
    }

    @Override
    @Deprecated
    public String peekBlipId() {
      return GENERATED_BLIP_ID;
    }

    @Override
    public WaveletId newConversationWaveletId() {
      return GENERATED_WAVELET_ID;
    }

    @Override
    public WaveletId newConversationRootWaveletId() {
      return ROOT_WAVELET_ID;
    }

    @Override
    public String newDataDocumentId() {
      throw new UnsupportedOperationException("Unsupported for test");
    }


    @Override
    public WaveletId newUserDataWaveletId(String address) {
      return new IdGeneratorImpl("example.com", null).newUserDataWaveletId(address);
    }

    @Override
    public WaveId newWaveId() {
      throw new UnsupportedOperationException("Unsupported for test");
    }

    @Override
    public String newId(String namespace) {
      throw new UnsupportedOperationException("Unsupported for test");
    }

    @Override
    public String newUniqueToken() {
      throw new UnsupportedOperationException("Unsupported for test");
    }

    @Override
    public String newBlockId() {
      throw new UnsupportedOperationException("Unsupported for test");
    }

    @Override
    public String getDefaultDomain() {
      throw new UnsupportedOperationException("Unsupported for test");
    }
  };

  private final PrintLogger logger = new PrintLogger();

  /**
   * {@inheritDoc}
   */
  @Override
  protected void tearDown() {
    System.out.println(logger);
  }

  /**
   * Because most of CcBasedWaveView is wiring up the various components, we simply test
   * that all the wiring are correct here rather than going to exhaustively testing every case.
   */
  public void testSimpleSunnyDayCase() {
    /*
    // This mock accepts all service calls.  This is the wrong testing
    // layer to be placing expectations on the mechanisms of the 3
    // layers between the CcBasedWaveView and the underlying WaveViewService.
    MockWaveViewService waveViewService = new MockWaveViewService();

    OperationChannelMultiplexerImpl.LoggerContext loggers =
        new OperationChannelMultiplexerImpl.LoggerContext(logger, logger, logger, logger);
    OperationChannelMultiplexerImpl mux = new OperationChannelMultiplexerImpl(WAVE_ID,
        ViewChannelImpl.factory(waveViewService, logger),
        WaveletDataImpl.Factory.create(docFactory),
        loggers, null, new ImmediateExcecutionScheduler(), FakeHashedVersionFactory.INSTANCE);
    CcBasedWaveView waveView = CcBasedWaveViewImpl.create(docFactory, SCHEMAS, WAVE_ID, USER_ID,
        mux, idGenerator, logger, new BasicWaveletOperationContextFactory(USER_ID), 
        ParticipationHelper.IGNORANT, null, WaveletConfigurator.ADD_CREATOR,
        DuplexOpSinkFactory.PASS_THROUGH);

    MockWaveViewListener viewListener = new MockWaveViewListener();
    waveView.addListener(viewListener);

    // Open the view and expect the open call on the service.
    MockOpenListener openListener = new MockOpenListener();
    
    CcBasedWavelet createdWavelet = waveView.createRoot();
    
    List<KnownWavelet> knownWavelets = new ArrayList<KnownWavelet>();
    knownWavelets.add(new KnownWavelet(createdWavelet.getWaveletData(),
        HashedVersion.unsigned(0), Accessibility.READ_WRITE));
    waveView.open(openListener, knownWavelets);
    assertEquals(1, waveViewService.opens.size());

    WaveViewService.OpenChannelStreamCallback openCallback = waveViewService.lastOpen().callback;

    // Receive a new wavelet from the server.
    openCallback.onWaveletOpen(CHANNEL, HashedVersion.unsigned(0), null, HashedVersion.unsigned(0));
    assertTrue(openListener.opened);
    
    assertNotNull(viewListener.addedWavelet);
    assertEquals(ROOT_WAVELET_ID, createdWavelet.getId());
    viewListener.reset();

    // Ack the configurator's add-participant
    assertEquals(1, waveViewService.submits.size());
    waveViewService.lastSubmit().callback
        .onResponse(1, HashedVersion.of(1L, sig(111)), TIMESTAMP, new ReturnStatus(ReturnCode.OK));

    DeltaTestUtil util = new DeltaTestUtil(USER_ID);

    // Receive an operation from the server that doesn't affect the document.
    TransformedWaveletDelta delta1 =
        util.delta(1L, util.addParticipant(new ParticipantId("reuben@example.com")));
    openCallback.onUpdate(DeltaSequence.of(delta1), null);

    assertEquals(2, createdWavelet.getParticipantIds().size());

    // createDocument an empty blip edit (i.e. implicit creation) and expect flush
    TransformedWaveletDelta delta2 = util.delta(2L, util.noOpDocOp(GENERATED_BLIP_ID));
    openCallback.onUpdate(DeltaSequence.of(delta2), null);
    
    doc.expectedCall(MockCcDocument.MethodCall.FLUSH);
    doc.expectedCall(MockCcDocument.MethodCall.CONSUME);
    doc.expectedNothing();

    // now hold back flush on another server op
    doc.flush_return = false;
    TransformedWaveletDelta delta3 = util.delta(3L, util.noOpDocOp(GENERATED_BLIP_ID));
    openCallback.onUpdate(DeltaSequence.of(delta3), null);
    MockCcDocument.MethodCallContext callContext =
        doc.expectedCall(MockCcDocument.MethodCall.FLUSH);
    doc.expectedNothing();

    // now we are ready to get the ops
    doc.flush_return = true;
    callContext.command.run();
    doc.expectedCall(MockCcDocument.MethodCall.FLUSH);
    doc.expectedCall(MockCcDocument.MethodCall.CONSUME);
    doc.expectedNothing();

    // close the view
    waveView.close();
    assertEquals(1, waveViewService.closes.size());
    waveViewService.lastClose().callback.onSuccess();
    */
  }

  private static byte[] sig(int s) {
    return new byte[] { (byte) s, (byte) s, (byte) s, (byte) s };
  }
}
