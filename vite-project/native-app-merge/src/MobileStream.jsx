import { useEffect, useRef, useState } from "react";
import { STREAM_BASE_URL } from "./services/api";

function buildWebSocketUrl(path) {
  const url = new URL(path, STREAM_BASE_URL);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
}

export default function MobileStream() {
  const videoRef = useRef(null);
  const canvasRef = useRef(null);
  const wsRef = useRef(null);
  const [streaming, setStreaming] = useState(false);

  const speakLocal = (text) => {
    if (!window.speechSynthesis) return;
    const msg = new SpeechSynthesisUtterance(text);
    msg.lang = "ko-KR";
    msg.rate = 1.1;
    // 💡 TTS 음성이 씹히거나 밀리지 않도록 큐를 초기화하고 즉시 재생
    window.speechSynthesis.cancel(); 
    window.speechSynthesis.speak(msg);
  };

  const startStreaming = async () => {
    try {
      if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        throw new Error("브라우저가 카메라 API를 지원하지 않습니다.");
      }

      // 💡 딜레이 최소화를 위해 카메라 입력 해상도 자체를 480x360으로 낮춰서 가져옵니다.
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: "environment", width: 480, height: 360 },
        audio: false
      });
      
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
      }

      const wsUrl = buildWebSocketUrl("/ws/stream");
      wsRef.current = new WebSocket(wsUrl);
      
      wsRef.current.onopen = () => {
        console.log("WebSocket Connected!");
        speakLocal("시스템이 연결되었습니다. 안내를 시작합니다.");
        setStreaming(true); 
      };

      wsRef.current.onmessage = (event) => {
        const messageFromServer = event.data;
        console.log("🤖 AI Alert:", messageFromServer);
        speakLocal(messageFromServer); // 서버에서 온 위험 경고 TTS 출력
      };

      wsRef.current.onerror = (err) => {
        console.error("WebSocket Error:", err);
        stopStreaming();
      };

      wsRef.current.onclose = () => {
        console.log("WebSocket Closed");
        stopStreaming();
      };

    } catch (err) {
      alert("카메라를 켤 수 없습니다: " + err.message);
    }
  };

  const stopStreaming = () => {
    setStreaming(false);
    if (videoRef.current && videoRef.current.srcObject) {
      videoRef.current.srcObject.getTracks().forEach(track => track.stop());
      videoRef.current.srcObject = null;
    }
    if (wsRef.current) {
      if (wsRef.current.readyState === WebSocket.OPEN) {
        wsRef.current.close();
      }
      wsRef.current = null;
    }
    window.speechSynthesis.cancel();
  };

  // 💡 [핵심 수정] 터틀봇 Compressed Image 메커니즘 적용
  useEffect(() => {
    let intervalId;
    if (streaming) {
      intervalId = setInterval(() => {
        if (videoRef.current && canvasRef.current && wsRef.current?.readyState === WebSocket.OPEN) {
          const video = videoRef.current;
          const canvas = canvasRef.current;
          const ctx = canvas.getContext("2d");

          // 캔버스 크기도 연산 효율을 위해 소형화
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
          
          // 💡 압축률을 0.2(20% 화질)로 극단적으로 낮춰 패킷 크기를 최소화합니다. (YOLO 인식에는 문제 없음)
          canvas.toBlob((blob) => {
            if (blob && wsRef.current?.readyState === WebSocket.OPEN) {
              blob.arrayBuffer().then((buffer) => {
                if (wsRef.current?.readyState === WebSocket.OPEN) {
                  wsRef.current.send(buffer);
                }
              });
            }
          }, "image/jpeg", 0.2); 
        }
      }, 100); // 초당 10프레임 전송 (스트리밍 안정성 확보)
    }
    return () => clearInterval(intervalId);
  }, [streaming]);

  return (
    <div style={{ padding: 20, textAlign: "center", fontFamily: "sans-serif", background: "#f5f5f5", minHeight: "100vh" }}>
      <h2 style={{ color: "#333" }}>📱 현장 카메라 & 안내 가이드</h2>
      <div style={{ marginBottom: 20 }}>
        {!streaming ? (
          <button onClick={startStreaming} style={{ padding: "16px 32px", background: "#0055FF", color: "white", border: "none", borderRadius: 12, fontWeight: "bold", fontSize: 18 }}>
            안내 및 방송 시작
          </button>
        ) : (
          <button onClick={stopStreaming} style={{ padding: "16px 32px", background: "#FF1744", color: "white", border: "none", borderRadius: 12, fontWeight: "bold", fontSize: 18 }}>
            안내 종료
          </button>
        )}
      </div>
      <video ref={videoRef} autoPlay playsInline muted style={{ width: "100%", maxWidth: 500, borderRadius: 16 }} />
      {/* 💡 전송용 캔버스 해상도를 320x240으로 낮춰 전송 속도 극대화 */}
      <canvas ref={canvasRef} width="320" height="240" style={{ display: "none" }} />
    </div>
  );
}
