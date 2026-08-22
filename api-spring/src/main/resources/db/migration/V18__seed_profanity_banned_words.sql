INSERT INTO banned_word (word, category, match_type) VALUES
  ('씨발', 'PROFANITY', 'CONTAINS'),
  ('개새끼', 'PROFANITY', 'CONTAINS')
ON CONFLICT (word, category) DO NOTHING;
