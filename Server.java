/* ------------------
   Server
   usage: java Server [RTSP listening port]
   ---------------------- */

   import java.io.*;
   import java.net.*;
   import java.awt.*;
   import java.util.*;
   import java.awt.event.*;
   import javax.swing.*;
   import javax.swing.Timer;
   import java.awt.image.*;
   import javax.imageio.*;
   import javax.imageio.stream.ImageOutputStream;
   
   public class Server extends JFrame implements ActionListener {
   
       //RTP variables:
       //----------------
       DatagramSocket RTPsocket;   //socket to be used to send UDP packets
       DatagramPacket senddp;      //UDP packet contendo os quadros de vídeo
   
       InetAddress ClientIPAddr;   //endereço IP do cliente
       int RTP_dest_port = 0;      //porta de destino para pacotes RTP
       int RTSP_dest_port = 0;
   
       //GUI:
       //----------------
       JLabel label;
   
       //Video variables:
       //----------------
       int imagenb = 0;            //número da imagem atual
       VideoStream video;          //objeto para acessar os quadros do vídeo
       static int MJPEG_TYPE = 26; //RTP payload type para MJPEG
       static int FRAME_PERIOD = 100;     //Período entre frames (ms)
       static int VIDEO_LENGTH  = 500;    //Tamanho (em quadros) do vídeo
   
       Timer timer;    //timer para enviar quadros de acordo com a taxa de vídeo
       byte[] buf;     //buffer para armazenar os quadros a enviar
       int sendDelay;  //atraso para envio dos quadros
   
       //RTSP variables
       //----------------
       //Estados do servidor RTSP
       final static int INIT    = 0;
       final static int READY   = 1;
       final static int PLAYING = 2;
       //Tipos de mensagem RTSP
       final static int SETUP    = 3;
       final static int PLAY     = 4;
       final static int PAUSE    = 5;
       final static int TEARDOWN = 6;
       final static int DESCRIBE = 7;
   
       static int state;              //estado atual do servidor RTSP
       Socket RTSPsocket;             //socket para envio/recebimento de mensagens RTSP
       static BufferedReader RTSPBufferedReader;
       static BufferedWriter RTSPBufferedWriter;
       static String VideoFileName;   //nome do arquivo de vídeo requisitado
       static String RTSPid = UUID.randomUUID().toString(); //ID da sessão RTSP
       int RTSPSeqNb = 0;            //número de sequência das mensagens RTSP
   
       //Performance: compressão de imagem (não envolve RTCP)
       ImageTranslator imgTranslator;
   
       final static String CRLF = "\r\n";
   
       //--------------------------------
       //Constructor
       //--------------------------------
       public Server() {
   
           //init Frame
           super("RTSP Server");
   
           //init RTP sending Timer
           sendDelay = FRAME_PERIOD;
           timer = new Timer(sendDelay, this);
           timer.setInitialDelay(0);
           timer.setCoalesce(true);
   
           //aloca memória para o buffer de envio
           buf = new byte[20000]; 
   
           //Handler para fechar a janela principal
           addWindowListener(new WindowAdapter() {
               public void windowClosing(WindowEvent e) {
                   //para o timer e encerra
                   timer.stop();
                   System.exit(0);
               }
           });
   
           //GUI:
           label = new JLabel("Send frame #        ", JLabel.CENTER);
           getContentPane().add(label, BorderLayout.CENTER);
   
           //Configura a qualidade de compressão da imagem, se desejado
           imgTranslator = new ImageTranslator(0.8f);
       }
   
       //------------------------------------
       //main
       //------------------------------------
       public static void main(String argv[]) throws Exception {
           //cria o objeto Server
           Server server = new Server();
   
           //mostra a GUI
           server.pack();
           server.setVisible(true);
           server.setSize(new Dimension(400, 200));
   
           //porta RTSP a partir da linha de comando
           int RTSPport = Integer.parseInt(argv[0]);
           server.RTSP_dest_port = RTSPport;
   
           //cria o socket de escuta para a sessão RTSP
           ServerSocket listenSocket = new ServerSocket(RTSPport);
           server.RTSPsocket = listenSocket.accept();
           listenSocket.close();
   
           //pega o endereço IP do cliente
           server.ClientIPAddr = server.RTSPsocket.getInetAddress();
   
           //estado RTSP inicial
           state = INIT;
   
           //configura os filtros de entrada e saída
           RTSPBufferedReader = new BufferedReader(new InputStreamReader(server.RTSPsocket.getInputStream()));
           RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(server.RTSPsocket.getOutputStream()));
   
           //aguarda a mensagem SETUP do cliente
           int request_type;
           boolean done = false;
           while(!done) {
               request_type = server.parseRequest(); //bloqueante
   
               if (request_type == SETUP) {
                   done = true;
   
                   //atualiza o estado do servidor
                   state = READY;
                   System.out.println("New RTSP state: READY");
   
                   //envia resposta
                   server.sendResponse();
   
                   //inicializa o objeto VideoStream
                   server.video = new VideoStream(VideoFileName);
   
                   //inicializa o socket RTP
                   server.RTPsocket = new DatagramSocket();
               }
           }
   
           //loop para lidar com requisições RTSP
           while(true) {
               //parse da requisição
               request_type = server.parseRequest(); //bloqueante
   
               if ((request_type == PLAY) && (state == READY)) {
                   //resposta
                   server.sendResponse();
                   //inicia o timer
                   server.timer.start();
   
                   //atualiza estado
                   state = PLAYING;
                   System.out.println("New RTSP state: PLAYING");
               }
               else if ((request_type == PAUSE) && (state == PLAYING)) {
                   //resposta
                   server.sendResponse();
                   //para o timer
                   server.timer.stop();
   
                   //atualiza estado
                   state = READY;
                   System.out.println("New RTSP state: READY");
               }
               else if (request_type == TEARDOWN) {
                   //resposta
                   server.sendResponse();
                   //para o timer
                   server.timer.stop();
                   //fecha sockets
                   server.RTSPsocket.close();
                   server.RTPsocket.close();
                   System.exit(0);
               }
               else if (request_type == DESCRIBE) {
                   System.out.println("Received DESCRIBE request");
                   server.sendDescribe();
               }
           }
       }
   
       //------------------------
       //Handler para o timer (envio de quadros via RTP)
       //------------------------
       public void actionPerformed(ActionEvent e) {
           //se ainda há quadros para enviar
           if (imagenb < VIDEO_LENGTH) {
               //incrementa o índice do quadro
               imagenb++;
   
               try {
                   //pega o próximo quadro do vídeo e seu tamanho
                   int image_length = video.getnextframe(buf);
   
                   //caso deseje comprimir sempre (independente de RTCP)
                   //exemplo de compressão simples:
                   /*
                   byte[] frame = imgTranslator.compress(Arrays.copyOfRange(buf, 0, image_length));
                   image_length = frame.length;
                   System.arraycopy(frame, 0, buf, 0, image_length);
                   */
   
                   //constrói o pacote RTP
                   RTPpacket rtp_packet = new RTPpacket(MJPEG_TYPE, imagenb, imagenb*FRAME_PERIOD, buf, image_length);
   
                   //obtém o tamanho total do pacote
                   int packet_length = rtp_packet.getlength();
   
                   //copia o bitstream do pacote para um array de bytes
                   byte[] packet_bits = new byte[packet_length];
                   rtp_packet.getpacket(packet_bits);
   
                   //envia o pacote por UDP
                   senddp = new DatagramPacket(packet_bits, packet_length, ClientIPAddr, RTP_dest_port);
                   RTPsocket.send(senddp);
   
                   System.out.println("Send frame #" + imagenb + ", Frame size: " + image_length + " (" + buf.length + ")");
                   //exibe o cabeçalho
                   rtp_packet.printheader();
   
                   //atualiza GUI
                   label.setText("Send frame #" + imagenb);
               }
               catch(Exception ex) {
                   System.out.println("Exception caught: "+ex);
                   System.exit(0);
               }
           }
           else {
               //se chegamos ao final do vídeo
               timer.stop();
           }
       }
   
       //------------------------------------
       //Classe para compressão de imagens (não envolve RTCP)
       //------------------------------------
       class ImageTranslator {
   
           private float compressionQuality;
           private ByteArrayOutputStream baos;
           private BufferedImage image;
           private Iterator<ImageWriter> writers;
           private ImageWriter writer;
           private ImageWriteParam param;
           private ImageOutputStream ios;
   
           public ImageTranslator(float cq) {
               compressionQuality = cq;
   
               try {
                   baos = new ByteArrayOutputStream();
                   ios  = ImageIO.createImageOutputStream(baos);
   
                   writers = ImageIO.getImageWritersByFormatName("jpeg");
                   writer  = writers.next();
                   writer.setOutput(ios);
   
                   param = writer.getDefaultWriteParam();
                   param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                   param.setCompressionQuality(compressionQuality);
               } 
               catch (Exception ex) {
                   System.out.println("Exception caught: "+ex);
                   System.exit(0);
               }
           }
   
           public byte[] compress(byte[] imageBytes) {
               try {
                   baos.reset();
                   image = ImageIO.read(new ByteArrayInputStream(imageBytes));
                   writer.write(null, new IIOImage(image, null, null), param);
               } 
               catch (Exception ex) {
                   System.out.println("Exception caught: "+ex);
                   System.exit(0);
               }
               return baos.toByteArray();
           }
   
           public void setCompressionQuality(float cq) {
               compressionQuality = cq;
               param.setCompressionQuality(compressionQuality);
           }
       }
   
       //------------------------------------
       //Parse RTSP Request
       //------------------------------------
       private int parseRequest() {
           int request_type = -1;
           try {
               //lê a linha de requisição e identifica o tipo
               String RequestLine = RTSPBufferedReader.readLine();
               System.out.println("RTSP Server - Received from Client:");
               System.out.println(RequestLine);
   
               StringTokenizer tokens = new StringTokenizer(RequestLine);
               String request_type_string = tokens.nextToken();
   
               //mapeia a string para um tipo de requisição
               if (request_type_string.compareTo("SETUP") == 0)
                   request_type = SETUP;
               else if (request_type_string.compareTo("PLAY") == 0)
                   request_type = PLAY;
               else if (request_type_string.compareTo("PAUSE") == 0)
                   request_type = PAUSE;
               else if (request_type_string.compareTo("TEARDOWN") == 0)
                   request_type = TEARDOWN;
               else if (request_type_string.compareTo("DESCRIBE") == 0)
                   request_type = DESCRIBE;
   
               if (request_type == SETUP) {
                   //extrai o nome do arquivo de vídeo
                   VideoFileName = tokens.nextToken();
               }
   
               //lê a linha CSeq
               String SeqNumLine = RTSPBufferedReader.readLine();
               System.out.println(SeqNumLine);
               tokens = new StringTokenizer(SeqNumLine);
               tokens.nextToken();
               RTSPSeqNb = Integer.parseInt(tokens.nextToken());
   
               //lê a última linha
               String LastLine = RTSPBufferedReader.readLine();
               System.out.println(LastLine);
   
               tokens = new StringTokenizer(LastLine);
               if (request_type == SETUP) {
                   //extrai a porta RTP de destino
                   for (int i=0; i<3; i++)
                       tokens.nextToken();
                   RTP_dest_port = Integer.parseInt(tokens.nextToken());
               }
               else if (request_type == DESCRIBE) {
                   tokens.nextToken();
                   String describeDataType = tokens.nextToken();
               }
               else {
                   //caso contrário, a última linha é a SessionId
                   tokens.nextToken(); //pula "Session:"
                   RTSPid = tokens.nextToken();
               }
           } 
           catch(Exception ex) {
               System.out.println("Exception caught: "+ex);
               System.exit(0);
           }
   
           return(request_type);
       }
   
       //Cria a resposta DESCRIBE em formato SDP para a mídia atual
       private String describe() {
           StringWriter writer1 = new StringWriter();
           StringWriter writer2 = new StringWriter();
   
           //Corpo (SDP)
           writer2.write("v=0" + CRLF);
           writer2.write("m=video " + RTSP_dest_port + " RTP/AVP " + MJPEG_TYPE + CRLF);
           writer2.write("a=control:streamid=" + RTSPid + CRLF);
           writer2.write("a=mimetype:string;\"video/MJPEG\"" + CRLF);
           String body = writer2.toString();
   
           //Cabeçalhos
           writer1.write("Content-Base: " + VideoFileName + CRLF);
           writer1.write("Content-Type: application/sdp" + CRLF);
           writer1.write("Content-Length: " + body.length() + CRLF);
           writer1.write(body);
   
           return writer1.toString();
       }
   
       //------------------------------------
       //Envia resposta RTSP
       //------------------------------------
       private void sendResponse() {
           try {
               RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF);
               RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);
               RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);
               RTSPBufferedWriter.flush();
               System.out.println("RTSP Server - Sent response to Client.");
           } 
           catch(Exception ex) {
               System.out.println("Exception caught: "+ex);
               System.exit(0);
           }
       }
   
       private void sendDescribe() {
           String des = describe();
           try {
               RTSPBufferedWriter.write("RTSP/1.0 200 OK" + CRLF);
               RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);
               RTSPBufferedWriter.write(des);
               RTSPBufferedWriter.flush();
               System.out.println("RTSP Server - Sent response to Client.");
           } 
           catch(Exception ex) {
               System.out.println("Exception caught: "+ex);
               System.exit(0);
           }
       }
   }
   