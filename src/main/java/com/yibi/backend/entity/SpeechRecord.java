package com.yibi.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Speech Record entity — maps to `speech_records` table.
 *
 * Data flow:
 * 1. User records audio via Web Audio API (React)
 * 2. Audio blob → POST /api/speech/score (Spring Boot)
 * 3. Spring Boot forwards to FastAPI /score-speech (Whisper ASR)
 * 4. Whisper returns transcript → accuracy computed
 * 5. SpeechRecord saved with audioUrl (MinIO) + score
 */
@Entity
@Table(name = "speech_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SpeechRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Optional: which vocabulary word was being practiced */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vocabulary_id")
    private Vocabulary vocabulary;

    /** The correct Chinese text the user was supposed to pronounce */
    @Column(name = "target_text", nullable = false, length = 500)
    private String targetText;

    /** MinIO URL of the user's recorded audio file */
    @Column(name = "audio_url", length = 500)
    private String audioUrl;

    /** Raw text returned by Whisper ASR model */
    @Column(name = "whisper_transcript", length = 500)
    private String whisperTranscript;

    /** Computed accuracy 0.00–100.00 (character match ratio × 100) */
    @Column(name = "accuracy_score", precision = 5, scale = 2)
    private BigDecimal accuracyScore;

    @Column(name = "recorded_at", nullable = false)
    @Builder.Default
    private LocalDateTime recordedAt = LocalDateTime.now();
}
