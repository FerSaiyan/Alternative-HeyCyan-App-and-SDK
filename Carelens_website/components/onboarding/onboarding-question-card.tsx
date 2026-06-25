"use client";

import { useRef } from "react";
import { useGSAP } from "@gsap/react";
import gsap from "gsap";

type OnboardingQuestionCardProps = {
  question: string;
  questionNumber: number;
  options: string[];
  name: string;
  selectedValue?: string;
  onSelectionChange?: (value: string) => void;
};

export function OnboardingQuestionCard({
  question,
  questionNumber,
  options,
  name,
  selectedValue,
}: OnboardingQuestionCardProps) {
  const cardRef = useRef<HTMLFieldSetElement>(null);

  useGSAP(
    () => {
      if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
        return;
      }

      gsap.fromTo(
        cardRef.current,
        { opacity: 0, y: 16 },
        { opacity: 1, y: 0, duration: 0.5, ease: "power2.out" },
      );
    },
    { scope: cardRef },
  );

  return (
    <fieldset
      ref={cardRef}
      className="onboarding-question-card group"
    >
      <legend className="sr-only">{question}</legend>
      <div className="flex items-start gap-3">
        <span className="question-number">{String(questionNumber).padStart(2, "0")}</span>
        <p className="question-text">{question}</p>
      </div>
      <div className="options-grid">
        {options.map((option) => (
          <label key={`${name}-${option}`} className="option-chip">
            <input
              type="radio"
              name={name}
              value={option}
              defaultChecked={selectedValue === option}
              required
              className="sr-only"
            />
            <span className="option-chip-content">{option}</span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
