-- 금칙어 시드 데이터 (가드레일 최소 세트)

INSERT INTO banned_word (word, category, match_type) VALUES
  ('환불', 'COMPENSATION', 'CONTAINS'),
  ('보상', 'COMPENSATION', 'CONTAINS'),
  ('무료로 드리', 'COMPENSATION', 'CONTAINS'),
  ('할인해 드리', 'COMPENSATION', 'CONTAINS'),
  ('쿠폰 드리', 'COMPENSATION', 'CONTAINS'),
  ('배민', 'COMPETITOR', 'CONTAINS'),
  ('쿠팡이츠', 'COMPETITOR', 'CONTAINS'),
  ('요기요', 'COMPETITOR', 'CONTAINS'),
  ('\d{2,3}-\d{3,4}-\d{4}', 'PII', 'REGEX'),
  ('치료', 'MEDICAL', 'CONTAINS'),
  ('효능', 'MEDICAL', 'CONTAINS');
