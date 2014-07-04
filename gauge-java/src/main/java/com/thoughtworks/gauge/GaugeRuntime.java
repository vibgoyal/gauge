package com.thoughtworks.gauge;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import main.Messages;
import org.reflections.Configuration;
import org.reflections.Reflections;
import org.reflections.scanners.MethodAnnotationsScanner;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.HashMap;
import java.util.Set;

public class GaugeRuntime {

    private static MessageLength getMessageLength(InputStream is) throws IOException {
        CodedInputStream codedInputStream = CodedInputStream.newInstance(is);
        long size = codedInputStream.readRawVarint64();
        return new MessageLength(size, codedInputStream);
    }

    private static byte[] toBytes(MessageLength messageLength) throws IOException {
        long messageSize = messageLength.length;
        CodedInputStream stream = messageLength.remainingStream;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        for (int i = 0; i < messageSize; i++) {
            outputStream.write(stream.readRawByte());
        }

        return outputStream.toByteArray();
    }

    private static void writeMessage(Socket socket, Messages.Message message) throws IOException {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        CodedOutputStream cos = CodedOutputStream.newInstance(stream);
        byte[] bytes = message.toByteArray();
        cos.writeRawVarint64(bytes.length);
        cos.flush();
        stream.write(bytes);
        socket.getOutputStream().write(stream.toByteArray());
        socket.getOutputStream().flush();
    }

    private static void dispatchMessages(Socket socket, HashMap<Messages.Message.MessageType, IMessageProcessor> messageProcessors) throws Exception {
        InputStream inputStream = socket.getInputStream();
        while (!socket.isClosed()) {
            try {
                MessageLength messageLength = getMessageLength(inputStream);
                byte[] bytes = toBytes(messageLength);
                Messages.Message message = Messages.Message.parseFrom(bytes);
                if (!messageProcessors.containsKey(message.getMessageType())) {
                    System.out.println("Invalid message");
                } else {
                    IMessageProcessor messageProcessor = messageProcessors.get(message.getMessageType());
                    Messages.Message response = messageProcessor.process(message);
                    writeMessage(socket, response);
                    if (message.getMessageType() == Messages.Message.MessageType.ExecutionEnding
                            || message.getMessageType() == Messages.Message.MessageType.KillProcessRequest) {
                        socket.close();
                        break;
                    }
                }
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                System.out.println(throwable.toString());
            }
        }
    }

    private static Socket connect() {
        String gaugePort = System.getenv("GAUGE_INTERNAL_PORT");
        if (gaugePort == null || gaugePort.equalsIgnoreCase("")) {
            throw new RuntimeException("GAUGE_INTERNAL_PORT not set");
        }
        int port = Integer.parseInt(gaugePort);
        Socket clientSocket;
        for (; ; ) {
            try {
                clientSocket = new Socket("localhost", port);
                break;
            } catch (Exception e) {
            }
        }

        return clientSocket;
    }

    public static void main(String[] args) throws Exception {
        HashMap<Messages.Message.MessageType, IMessageProcessor> messageProcessors = new HashMap<Messages.Message.MessageType, IMessageProcessor>() {{
            put(Messages.Message.MessageType.ExecutionStarting, new SuiteExecutionStartingProcessor());
            put(Messages.Message.MessageType.ExecutionEnding, new SuiteExecutionEndingProcessor());
            put(Messages.Message.MessageType.SpecExecutionStarting, new SpecExecutionStartingProcessor());
            put(Messages.Message.MessageType.SpecExecutionEnding, new SpecExecutionEndingProcessor());
            put(Messages.Message.MessageType.ScenarioExecutionStarting, new ScenarioExecutionStartingProcessor());
            put(Messages.Message.MessageType.ScenarioExecutionEnding, new ScenarioExecutionEndingProcessor());
            put(Messages.Message.MessageType.StepExecutionStarting, new StepExecutionStartingProcessor());
            put(Messages.Message.MessageType.StepExecutionEnding, new StepExecutionEndingProcessor());
            put(Messages.Message.MessageType.ExecuteStep, new ExecuteStepProcessor());
            put(Messages.Message.MessageType.StepValidateRequest, new ValidateStepProcessor());
            put(Messages.Message.MessageType.StepNamesRequest, new StepNamesRequestProcessor());
            put(Messages.Message.MessageType.KillProcessRequest, new KillProcessProcessor());
            put(Messages.Message.MessageType.RefactorRequest, new RefactorRequestProcessor());
        }};

        scanForStepImplementations();

        Socket socket = connect();
        dispatchMessages(socket, messageProcessors);
    }

    private static void scanForHooks(Reflections reflections) {
        HooksRegistry.setBeforeSuiteHooks(reflections.getMethodsAnnotatedWith(BeforeSuite.class));
        HooksRegistry.setAfterSuiteHooks(reflections.getMethodsAnnotatedWith(AfterSuite.class));
        HooksRegistry.setBeforeSpecHooks(reflections.getMethodsAnnotatedWith(BeforeSpec.class));
        HooksRegistry.setAfterSpecHooks(reflections.getMethodsAnnotatedWith(AfterSpec.class));
        HooksRegistry.setBeforeScenarioHooks(reflections.getMethodsAnnotatedWith(BeforeScenario.class));
        HooksRegistry.setAfterScenarioHooks(reflections.getMethodsAnnotatedWith(AfterScenario.class));
        HooksRegistry.setBeforeStepHooks(reflections.getMethodsAnnotatedWith(BeforeStep.class));
        HooksRegistry.setAfterStepHooks(reflections.getMethodsAnnotatedWith(AfterStep.class));
    }

    private static void scanForStepImplementations() {
        Configuration config = new ConfigurationBuilder()
                .setScanners(new MethodAnnotationsScanner())
                .addUrls(ClasspathHelper.forJavaClassPath());
        Reflections reflections = new Reflections(config);
        Set<Method> stepImplementations = reflections.getMethodsAnnotatedWith(Step.class);
        StepValueExtractor stepValueExtractor = new StepValueExtractor();
        for (Method method : stepImplementations) {
            Step annotation = method.getAnnotation(Step.class);
            if (annotation != null) {
                for (String stepName : annotation.value()) {
                    StepValueExtractor.StepValue stepValue = stepValueExtractor.getValue(stepName);
                    StepRegistry.addStepImplementation(stepValue.getValue(), method);
                }
            }
        }
        scanForHooks(reflections);
    }

    static class MessageLength {
        public long length;
        public CodedInputStream remainingStream;

        public MessageLength(long length, CodedInputStream remainingStream) {
            this.length = length;
            this.remainingStream = remainingStream;
        }
    }
}
