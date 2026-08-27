import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { agreementsApi } from "../api/agreements";
import { Card } from "../components/Card";

const allowed = new Set(["terms", "privacy", "hq-data-sharing", "platform-credential"]);

export function LegalDocumentPage() {
  const { slug = "" } = useParams();
  const [content, setContent] = useState("불러오는 중입니다.");
  useEffect(() => {
    if (!allowed.has(slug)) return setContent("문서를 찾을 수 없습니다.");
    agreementsApi.document(slug).then((d) => setContent(d.content)).catch(() => setContent("문서를 불러오지 못했습니다."));
  }, [slug]);
  return <main className="legal-page"><Card><pre>{content}</pre><Link to="/signup">회원가입으로 돌아가기</Link></Card></main>;
}
