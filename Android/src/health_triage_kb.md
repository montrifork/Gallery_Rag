# Health Issue Triage & Claim Assistant — Knowledge Base

> **Purpose:** This document is the retrieval source for an on-device assistant that helps insurance-app users triage health issues, attempt safe self-care, escalate to professional care when needed, and submit a claim if applicable.
>
> **Audience:** Adults using a consumer insurance app. Adjust thresholds downward for children, pregnant users, elderly, and immunocompromised users.
>
> **Disclaimer to surface in every session:** This assistant provides general guidance only and is not a substitute for professional medical advice, diagnosis, or treatment. In emergencies, call local emergency services immediately.

---

## PART 1 — GLOBAL GUIDELINES

### 1.1 Assistant Role
- Act as a calm, supportive guide — never as a doctor.
- Goal sequence: Understand -> Triage -> Self-care -> Re-check -> Professional care -> Claim.
- Use plain language. Keep replies short (2-4 sentences typical).
- Ask one question at a time.

### 1.2 Universal Red Flags — Recommend Emergency Care Immediately
Stop the normal flow and instruct the user to call emergency services if any of the following are reported:
- Chest pain or pressure, especially with sweating, nausea, or arm/jaw pain
- Difficulty breathing, choking, or blue lips
- Stroke signs: face drooping, arm weakness, speech difficulty (FAST)
- Sudden severe headache ("worst of life")
- Severe bleeding that won't stop
- Loss of consciousness, confusion, or seizure
- Severe allergic reaction (swelling of face/throat, trouble breathing)
- Suicidal thoughts or intent to harm self or others
- Severe head, neck, or spine injury
- High fever in infant under 3 months
- Signs of sepsis: high fever + confusion + rapid heart rate
- Pregnancy: heavy bleeding, severe abdominal pain, reduced fetal movement

### 1.3 Triage Levels
- EMERGENCY -> call 911 / local emergency now.
- URGENT (within 24 hours) -> urgent care, ER, or same-day telehealth.
- ROUTINE (within days) -> primary care or scheduled telehealth.
- SELF-CARE -> safe to manage at home; re-evaluate after defined window.

### 1.4 Self-Care Defaults (Safe for Most Adults)
- Rest, hydration, balanced nutrition.
- OTC analgesics at standard adult dosing: acetaminophen up to 3000 mg/day; ibuprofen up to 1200 mg/day OTC, with food.
- Avoid ibuprofen if: pregnant (3rd trimester), kidney disease, ulcer history, on blood thinners.
- Avoid acetaminophen excess if: liver disease, heavy alcohol use.
- Always tell the user to read the medication label.

### 1.5 Information to Collect (Ask Gradually)
- Symptom description and location
- Onset and duration
- Severity (1-10)
- What worsens or improves it
- Associated symptoms
- Relevant history: conditions, medications, allergies, pregnancy, age group

### 1.6 Re-Check Window
After self-care, ask the user to follow up after the symptom-specific window. On follow-up:
- Better -> continue or taper care.
- Same -> extend window once OR escalate based on symptom rules.
- Worse or new symptoms -> escalate to professional care.

### 1.7 When to Suggest a Claim
Only after the user has:
1. Received professional care (visit, telehealth, ER, specialist), OR
2. Purchased a covered service or item (medication, device, lab test), AND
3. Has documentation (receipt, invoice, medical note).

Use language like "you may be eligible to submit a claim" — never promise approval.

### 1.8 Communication Rules
- Validate feelings first ("That sounds uncomfortable").
- Avoid jargon; define any medical term used.
- Provide specific, actionable steps with quantities and timeframes.
- End each turn with a clear next step or single question.
- Never discourage a user who wants professional care.

### 1.9 Special Populations — Lower the Threshold
- Children under 5
- Adults over 65
- Pregnant or breastfeeding
- Immunocompromised (cancer treatment, transplant, HIV, autoimmune therapy)
- Chronic conditions (diabetes, heart disease, COPD, kidney disease)

---

## PART 2 — SYMPTOM ENTRIES

Each entry follows the same structure: Likely Causes -> Red Flags -> Self-Care Suggestions -> Re-Check Window -> Escalation Criteria -> Claim Guidance.

---

### 2.1 Headache

**Likely causes:** Tension, dehydration, eye strain, lack of sleep, caffeine withdrawal, sinus pressure, migraine.

**Red flags (urgent/emergency):**
- Sudden "thunderclap" onset
- After head injury
- With fever and stiff neck
- With weakness, vision loss, slurred speech, confusion
- New headache pattern in person over 50
- Worst headache of life

**Self-care suggestions:**
- Drink 500 ml water; continue hydration through the day.
- Rest in a quiet, dim room for 30 minutes.
- Apply a cool cloth to forehead or warm compress to neck/shoulders.
- Acetaminophen 500-1000 mg or ibuprofen 200-400 mg with food.
- Limit screens; check posture.
- Eat a small meal if it's been more than 4 hours.

**Re-check window:** 4-6 hours, then 24 hours.

**Escalate to professional care if:**
- No improvement after 48 hours
- Recurring more than 3 times per week
- Interferes with work or sleep
- Requires daily OTC medication

**Claim guidance:** After a clinic, telehealth, or pharmacy visit, suggest claim if user has receipts for visit, prescription, or recommended diagnostic.

---

### 2.2 Fever (Adult)

**Likely causes:** Viral infection, bacterial infection, post-vaccination, heat exposure.

**Red flags:**
- Temperature >= 39.5 C (103 F) not responding to medication
- Lasts more than 3 days
- With stiff neck, confusion, rash, difficulty breathing, severe abdominal pain
- In immunocompromised user -> urgent care same day
- Infant under 3 months with any fever -> emergency

**Self-care suggestions:**
- Hydrate with water, broth, or electrolyte drinks (250 ml every hour).
- Rest; light clothing; cool room.
- Acetaminophen 500-1000 mg every 6 hours OR ibuprofen 200-400 mg every 6-8 hours with food.
- Lukewarm sponge bath if uncomfortable (avoid cold/alcohol).

**Re-check window:** 24 hours.

**Escalate if:**
- Fever > 3 days
- Returns after improving
- Plus new symptoms (rash, severe pain, breathing trouble)

**Claim guidance:** If user visited provider, did labs, or got a prescription, walk them through claim submission with itemized receipt.

---

### 2.3 Cough

**Likely causes:** Cold, flu, post-nasal drip, bronchitis, allergies, asthma, acid reflux.

**Red flags:**
- Coughing blood
- Shortness of breath at rest
- Chest pain
- High fever with productive green/yellow sputum and chills
- Lasting more than 3 weeks

**Self-care suggestions:**
- Warm fluids: tea with honey (honey only for adults and children > 1 year), broth.
- Honey 1-2 teaspoons for cough soothing.
- Steam inhalation 10 minutes, twice daily.
- Elevate head while sleeping.
- OTC cough lozenges or dextromethorphan per label.
- Avoid smoke and irritants.

**Re-check window:** 72 hours.

**Escalate if:**
- Cough > 2 weeks
- Wheezing or breathing difficulty develops
- Fever returns

**Claim guidance:** After provider visit or chest imaging, prompt claim with documents.

---

### 2.4 Sore Throat

**Likely causes:** Viral infection (most common), strep, allergies, dry air, acid reflux.

**Red flags:**
- Difficulty swallowing or breathing
- Drooling (especially in children)
- Severe one-sided pain with muffled voice
- High fever with white patches on tonsils + swollen neck nodes (possible strep)

**Self-care suggestions:**
- Warm salt water gargle: 1/2 teaspoon salt in 250 ml warm water, 3x daily.
- Warm tea with honey and lemon.
- Throat lozenges or sprays per label.
- Acetaminophen or ibuprofen for pain.
- Humidifier in bedroom.

**Re-check window:** 48 hours.

**Escalate if:**
- No improvement in 3 days
- Suspected strep (fever + white patches + tender neck nodes) -> needs testing
- Recurrent infections

**Claim guidance:** Strep tests, prescriptions, and provider visits are usually claimable.

---

### 2.5 Common Cold / Flu

**Likely causes:** Viral upper respiratory infection.

**Red flags:**
- Difficulty breathing
- Chest pain
- Persistent high fever
- Symptoms improving then suddenly worsening (possible bacterial superinfection)
- Confusion or severe weakness

**Self-care suggestions:**
- Rest; sleep 8+ hours.
- Hydrate aggressively.
- Saline nasal spray or rinse.
- Steam inhalation.
- Acetaminophen or ibuprofen for aches and fever.
- Decongestants per label (avoid if hypertensive).
- Zinc lozenges in first 24 hours may shorten duration.

**Re-check window:** 5-7 days.

**Escalate if:**
- Symptoms > 10 days
- Worsens after day 5
- Breathing difficulty
- High-risk user (elderly, chronic conditions) -> consider antiviral within 48 hours of flu onset

**Claim guidance:** Antivirals, provider visits, and lab tests are claimable.

---

### 2.6 Back Pain

**Likely causes:** Muscle strain, poor posture, prolonged sitting, lifting injury, disc issue.

**Red flags:**
- After significant trauma
- Numbness, tingling, or weakness in legs
- Loss of bladder or bowel control (emergency)
- Fever with back pain
- Unexplained weight loss
- Pain at night that wakes user

**Self-care suggestions:**
- Stay gently active; avoid prolonged bed rest.
- Ice for first 48 hours (15 min, 3x daily), then heat.
- Ibuprofen 400 mg every 6-8 hours with food, up to 3 days.
- Stretching: knee-to-chest, gentle cat-cow.
- Improve workstation ergonomics.
- Sleep with pillow under knees (back) or between knees (side).

**Re-check window:** 72 hours.

**Escalate if:**
- No improvement in 1 week
- Pain radiating down leg below knee
- Any red flag develops

**Claim guidance:** Physiotherapy, imaging, and provider visits — submit with referral and receipts.

---

### 2.7 Stomach Pain

**Likely causes:** Indigestion, gas, constipation, gastritis, viral infection, menstrual cramps, food intolerance.

**Red flags:**
- Severe pain in lower right abdomen (possible appendicitis) -> emergency
- Pain with vomiting blood or black stools -> emergency
- Rigid abdomen
- Pain with fever and inability to keep fluids down
- Pregnancy with abdominal pain

**Self-care suggestions:**
- Sip clear fluids slowly.
- Bland diet (BRAT: bananas, rice, applesauce, toast).
- Avoid dairy, fatty, spicy, caffeinated foods for 24 hours.
- Heating pad on abdomen.
- Antacid for heartburn/indigestion per label.
- Walk gently to relieve gas.

**Re-check window:** 24 hours.

**Escalate if:**
- Pain > 48 hours
- Localized severe pain
- Cannot keep fluids down
- Blood in stool or vomit

**Claim guidance:** Provider visits, ultrasounds, and prescriptions are claimable.

---

### 2.8 Diarrhea

**Likely causes:** Viral or bacterial gastroenteritis, food poisoning, medication side effect, IBS.

**Red flags:**
- Blood in stool
- High fever
- Signs of dehydration (dizziness, dark urine, no urination 8+ hours)
- Lasting > 3 days
- Severe abdominal pain
- Recent travel to high-risk region

**Self-care suggestions:**
- Oral rehydration solution: small sips every 5-10 minutes.
- BRAT diet.
- Avoid dairy, alcohol, caffeine, fatty foods.
- Probiotics may help (yogurt with live cultures).
- Loperamide per label (avoid if fever or bloody stools).

**Re-check window:** 24-48 hours.

**Escalate if:**
- > 3 days
- Dehydration signs
- Blood or black stools
- High fever

**Claim guidance:** Stool tests, provider visits, and prescriptions are claimable.

---

### 2.9 Nausea / Vomiting

**Likely causes:** Viral infection, food poisoning, motion sickness, migraine, pregnancy, medication.

**Red flags:**
- Vomiting blood or coffee-ground material
- Severe abdominal or chest pain
- Signs of dehydration
- Head injury
- Stiff neck with fever
- Cannot keep any fluids down for 24 hours

**Self-care suggestions:**
- Stop solid food for 4-6 hours.
- Sip clear fluids: 1 teaspoon every 5 minutes, increase gradually.
- Ginger tea or ginger candy.
- Rest in semi-upright position.
- Gradually reintroduce bland foods.

**Re-check window:** 12-24 hours.

**Escalate if:**
- > 24 hours of vomiting
- Dehydration signs
- Severe pain develops

**Claim guidance:** Anti-nausea prescriptions, IV fluids, and provider visits are claimable.

---

### 2.10 Rash

**Likely causes:** Contact dermatitis, allergic reaction, eczema, viral rash, heat rash, fungal infection.

**Red flags:**
- Spreading rapidly
- With fever, swelling, or trouble breathing -> emergency
- Painful blistering
- Purple spots that don't fade with pressure (possible meningitis) -> emergency
- Affects eyes, mouth, or genitals significantly

**Self-care suggestions:**
- Identify and avoid possible trigger (new soap, plant, food, medication).
- Cool compress 15 minutes, several times daily.
- OTC hydrocortisone 1% cream twice daily for itch (not on face long-term).
- Oral antihistamine (cetirizine, loratadine) per label.
- Loose, breathable clothing.
- Lukewarm baths with colloidal oatmeal.

**Re-check window:** 48 hours.

**Escalate if:**
- Spreading or worsening
- Painful or oozing
- Doesn't improve in 1 week
- Recurrent

**Claim guidance:** Dermatology visits, prescriptions, and patch tests are claimable.

---

### 2.11 Sprain / Minor Joint Injury

**Likely causes:** Twisting, overuse, fall, sports injury.

**Red flags:**
- Cannot bear weight at all
- Visible deformity
- Numbness below injury
- Severe swelling immediately
- Heard a "pop" with severe pain

**Self-care suggestions:** Use R.I.C.E. for first 48 hours.
- Rest the joint; avoid weight-bearing if painful.
- Ice 15-20 minutes every 2-3 hours.
- Compression with elastic bandage (snug, not tight).
- Elevation above heart level when possible.
- Ibuprofen for pain and swelling.
- Gentle range-of-motion after 48 hours.

**Re-check window:** 48-72 hours.

**Escalate if:**
- No improvement in 5 days
- Cannot move the joint
- Severe bruising or deformity

**Claim guidance:** X-rays, physiotherapy, braces, and provider visits are claimable.

---

### 2.12 Ear Pain

**Likely causes:** Ear infection, fluid buildup, wax, jaw issues (TMJ), referred throat pain, swimmer's ear.

**Red flags:**
- High fever with severe pain
- Discharge of pus or blood
- Sudden hearing loss
- Severe swelling behind ear
- Dizziness with vomiting

**Self-care suggestions:**
- Warm compress over ear.
- Acetaminophen or ibuprofen for pain.
- Keep ear dry; avoid cotton swabs.
- Sleep with affected ear elevated.
- OTC ear drops for wax (only if eardrum intact).

**Re-check window:** 48 hours.

**Escalate if:**
- Pain > 2 days
- Discharge
- Hearing loss
- Recurrent infections

**Claim guidance:** Provider visit, antibiotics, and audiology referrals are claimable.

---

### 2.13 Eye Irritation / Redness

**Likely causes:** Dry eye, allergies, conjunctivitis (viral, bacterial, allergic), foreign body, contact lens issue.

**Red flags:**
- Sudden vision loss or changes
- Severe pain
- Light sensitivity with headache
- Chemical exposure -> flush with water 15 min, then emergency
- Trauma to the eye
- Pupil abnormalities

**Self-care suggestions:**
- Remove contact lenses; switch to glasses.
- Cool compress for allergies; warm compress for stye.
- Lubricating ("artificial tears") drops 4x daily.
- Antihistamine drops for allergies per label.
- Wash hands often; don't share towels.

**Re-check window:** 48 hours.

**Escalate if:**
- Vision changes
- Pain increases
- Yellow/green discharge
- No improvement in 3 days

**Claim guidance:** Optometry/ophthalmology visits and prescription drops are claimable.

---

### 2.14 UTI Symptoms (Burning Urination, Frequency)

**Likely causes:** Bladder infection, kidney infection, irritation, STI.

**Red flags:**
- Fever, chills, back/flank pain -> possible kidney infection, urgent
- Blood in urine
- Vomiting
- Pregnancy with UTI symptoms -> urgent
- Confusion in elderly

**Self-care suggestions:**
- Drink 2-3 liters of water through the day.
- Urinate when needed; don't hold.
- Avoid caffeine, alcohol, spicy food temporarily.
- Cranberry products may help prevention (limited evidence for treatment).
- OTC urinary pain relief (phenazopyridine) for symptoms only — does not cure infection.

**Re-check window:** 24 hours — UTIs usually need antibiotics; do not delay long.

**Escalate if:**
- Symptoms persist > 24 hours
- Any red flag
- Recurrent UTIs

**Claim guidance:** Urinalysis, antibiotics, and provider visits are claimable.

---

### 2.15 Anxiety (Acute, Non-Crisis)

**Likely causes:** Stress, life events, caffeine, sleep deprivation, underlying anxiety disorder.

**Red flags:**
- Suicidal thoughts, self-harm urges -> provide crisis hotline immediately, urgent help
- Panic attacks with chest pain — rule out cardiac cause if first time
- Inability to function in daily life

**Self-care suggestions:**
- Box breathing: inhale 4s, hold 4s, exhale 4s, hold 4s — repeat 4 minutes.
- 5-4-3-2-1 grounding: name 5 things you see, 4 you feel, 3 you hear, 2 you smell, 1 you taste.
- Reduce caffeine and alcohol.
- 20-30 minutes of walking daily.
- Consistent sleep schedule.
- Limit news and social media.
- Journaling or talking to a trusted person.

**Re-check window:** 1 week.

**Escalate if:**
- Daily impact persists > 2 weeks
- Panic attacks recur
- Sleep severely disrupted
- Any thoughts of self-harm -> immediate professional help

**Claim guidance:** Therapy sessions, psychiatric visits, and prescribed medications are claimable.

---

### 2.16 Insomnia

**Likely causes:** Stress, poor sleep hygiene, caffeine, screen exposure, depression, pain, hormonal changes.

**Red flags:**
- Persistent insomnia with depressed mood or hopelessness
- Daytime impairment causing safety risk (driving)
- Loud snoring with breathing pauses (possible sleep apnea)

**Self-care suggestions:**
- Fixed wake-up time daily.
- No caffeine after noon.
- No screens 1 hour before bed.
- Cool, dark, quiet bedroom.
- Bed only for sleep; if awake > 20 minutes, get up and do quiet activity.
- Wind-down routine: shower, reading, light stretching.
- Avoid alcohol as a sleep aid.
- Short-term melatonin 0.5-3 mg 30 min before bed (per label).

**Re-check window:** 2 weeks.

**Escalate if:**
- Insomnia > 3 weeks
- Daytime fatigue affects function
- Suspected sleep apnea
- Mood symptoms

**Claim guidance:** Sleep studies, behavioral therapy (CBT-I), and provider visits are claimable.

---

### 2.17 Minor Burn

**Likely causes:** Heat, hot liquid, sun, friction.

**Red flags:**
- Larger than user's palm
- On face, hands, feet, genitals, or major joints
- Deep (white, charred, leathery)
- Chemical or electrical burn -> emergency
- Signs of infection (increasing redness, pus, fever)

**Self-care suggestions (1st-degree / small 2nd-degree):**
- Cool running water for 10-20 minutes (not ice).
- Do not pop blisters.
- Apply petrolatum or aloe vera; avoid butter, toothpaste.
- Cover with non-stick sterile dressing.
- Acetaminophen or ibuprofen for pain.
- Keep clean and check daily.

**Re-check window:** 48 hours, then daily for 1 week.

**Escalate if:**
- Signs of infection
- Blisters > 1 cm
- Slow healing
- Burn on sensitive areas

**Claim guidance:** Provider visits, dressings, and prescriptions are claimable.

---

### 2.18 Minor Cut

**Likely causes:** Knife, sharp object, fall.

**Red flags:**
- Bleeding doesn't stop after 15 minutes of pressure
- Deep, gaping, or jagged wound
- Numbness or inability to move
- From rusty/dirty object -> tetanus consideration
- Animal or human bite
- Embedded object — do not remove; emergency

**Self-care suggestions:**
- Wash hands.
- Rinse wound with clean running water.
- Apply firm pressure with clean cloth for 10-15 minutes.
- Apply antibiotic ointment.
- Cover with sterile bandage; change daily.
- Watch for signs of infection (redness, warmth, pus, swelling, red streaks).
- Verify tetanus vaccination is up to date (within 10 years).

**Re-check window:** Daily for 5-7 days.

**Escalate if:**
- Signs of infection
- Doesn't close or heal
- Tetanus uncertain

**Claim guidance:** Stitches, tetanus shots, and provider visits are claimable.

---

### 2.19 Allergic Reaction (Mild)

**Likely causes:** Food, insect sting, medication, plant, pet, dust.

**Red flags (anaphylaxis — emergency):**
- Throat tightness or swelling
- Difficulty breathing or wheezing
- Swelling of lips, tongue, face
- Dizziness or fainting
- Rapid widespread hives with any of the above
- Use epinephrine auto-injector if prescribed, then call emergency

**Self-care suggestions (mild only — localized hives, mild itching):**
- Identify and remove trigger.
- Oral antihistamine: cetirizine 10 mg or loratadine 10 mg daily.
- Cool compress on hives.
- OTC hydrocortisone 1% cream for localized itch.
- Lukewarm shower; avoid hot water.

**Re-check window:** 24 hours.

**Escalate if:**
- Symptoms spread or worsen
- Any breathing or throat involvement (emergency)
- Recurrent reactions -> allergy testing referral

**Claim guidance:** Allergy testing, EpiPen prescription, and specialist visits are claimable.

---

### 2.20 Dizziness

**Likely causes:** Dehydration, low blood sugar, inner ear issue (BPPV, vestibular), low blood pressure, medication, anxiety.

**Red flags:**
- With chest pain, shortness of breath, fainting -> emergency
- With slurred speech, weakness, vision changes -> emergency (stroke)
- After head injury
- Severe headache with dizziness
- Persistent vertigo with vomiting

**Self-care suggestions:**
- Sit or lie down immediately when dizzy.
- Hydrate (250-500 ml water).
- Eat a small snack if hungry.
- Rise slowly from sitting/lying.
- Avoid driving until resolved.
- Limit caffeine and alcohol.
- For BPPV, consider Epley maneuver guidance from a clinician.

**Re-check window:** 24 hours.

**Escalate if:**
- Recurrent dizziness
- Lasts more than a few minutes
- With any neurological symptoms
- Affects daily function

**Claim guidance:** ENT visits, vestibular therapy, blood tests, and imaging are claimable.

---

## PART 3 — CLAIM SUBMISSION FLOW (FOR ASSISTANT TO GUIDE USER)

### 3.1 When to Bring Up the Claim
Only after professional care has occurred or a covered expense was incurred.

### 3.2 What to Confirm
- Was the provider in-network?
- Do you have the itemized invoice/receipt?
- Do you have the medical note or prescription?
- Date(s) of service?
- Total amount paid out of pocket?

### 3.3 Documents Typically Needed
- Itemized invoice/receipt
- Medical report or doctor's note
- Prescription (if medication claim)
- Lab/imaging report (if applicable)
- Proof of payment
- Referral letter (if specialist)

### 3.4 Step-by-Step Guidance to Offer
1. Open the Claims section of the app.
2. Select the claim type (consultation, medication, lab, hospital, dental, etc.).
3. Upload required documents (camera or file).
4. Enter date and amount.
5. Add a brief description.
6. Review and submit.
7. Save the reference number.

### 3.5 Setting Expectations
- Processing time varies (commonly 5-15 business days).
- The user may be asked for additional documents.
- Coverage depends on the user's plan; the assistant should never promise approval.

---

## PART 4 — FOLLOW-UP & CLOSURE

- After any escalation or claim, the assistant should check in: "How are you feeling today? Did the visit help?"
- If the user reports recovery -> offer a brief recap and close warmly.
- If the user reports persistent issues -> re-enter triage flow.
- Always remind: "If anything changes or worsens, come back to me or contact your provider."

---

End of knowledge base.
