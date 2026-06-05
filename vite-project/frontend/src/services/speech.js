export function speak(text) {
  if (!text || !window.speechSynthesis) {
    return false;
  }

  window.speechSynthesis.cancel();

  const utterance = new SpeechSynthesisUtterance(text);
  utterance.lang = "ko-KR";
  utterance.rate = 0.95;
  window.speechSynthesis.speak(utterance);

  return true;
}

export function stopSpeaking() {
  if (window.speechSynthesis) {
    window.speechSynthesis.cancel();
  }
}

export function listenOnce({ onResult, onError }) {
  const Recognition =
    window.SpeechRecognition || window.webkitSpeechRecognition;

  if (!Recognition) {
    onError("이 브라우저는 음성 입력을 지원하지 않습니다.");
    return null;
  }

  const recognition = new Recognition();
  recognition.lang = "ko-KR";
  recognition.interimResults = false;
  recognition.maxAlternatives = 1;

  recognition.onresult = (event) => {
    const transcript = event.results?.[0]?.[0]?.transcript || "";
    onResult(transcript);
  };

  recognition.onerror = () => {
    onError("음성 입력을 완료하지 못했습니다.");
  };

  recognition.start();

  return recognition;
}
