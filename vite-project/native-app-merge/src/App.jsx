import { useState, useEffect } from "react";
import KakaoMapView from "./KakaoMapView";
import MobileStream from "./MobileStream"; // 📱 새로 만든 스마트폰용 컴포넌트 불러오기

export default function App() {
  const [isMobile, setIsMobile] = useState(false);

  useEffect(() => {
    // 💡 주소창의 쿼리 스트링(?mode=mobile)이 있는지 검사합니다.
    const params = new URLSearchParams(window.location.search);
    if (params.get("mode") === "mobile") {
      setIsMobile(true);
    }
  }, []);

  // 조건 분기: mode=mobile이면 스마트폰 화면을, 없으면 기존 카카오맵 화면을 띄웁니다.
  return (
    <>
      {isMobile ? <MobileStream /> : <KakaoMapView />}
    </>
  );
}