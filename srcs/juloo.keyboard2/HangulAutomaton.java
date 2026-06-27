package juloo.keyboard2;

import android.view.inputmethod.InputConnection;

/**
 * Dubeolsik (두벌식) Korean syllable composition automaton.
 *
 * Manages the state of a single syllable being composed and communicates with
 * the InputConnection via setComposingText / commitText.
 *
 * State machine transitions:
 *   empty  + consonant  → pending chosung (State 1)
 *   empty  + vowel      → ㅇ+vowel syllable (State 2)
 *   State1 + vowel      → cho+jung syllable (State 2)
 *   State1 + consonant  → commit State1, start new State1
 *   State2 + consonant  → add as jongsung (State 3) or commit+new if not allowed
 *   State2 + vowel      → compound vowel (State 2) or commit+new ㅇ+vowel
 *   State3 + vowel      → split jongsung, commit first, start new (State 2)
 *   State3 + consonant  → compound jongsung (State 3) or commit+new (State 1)
 */
public class HangulAutomaton {

  private static final int HANGUL_BASE = 0xAC00;
  private static final int JUNGSUNG_COUNT = 21;
  private static final int JONGSUNG_COUNT = 28; // slot 0 = no final consonant

  // Chosung: valid initial consonants, index 0-18
  // ㄱ ㄲ ㄴ ㄷ ㄸ ㄹ ㅁ ㅂ ㅃ ㅅ ㅆ ㅇ ㅈ ㅉ ㅊ ㅋ ㅌ ㅍ ㅎ
  private static final char[] CHOSUNG = {
    'ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'
  };
  // Index of ㅇ in CHOSUNG (silent initial consonant for vowel-initial syllables)
  private static final int CHOSUNG_IEUNG = 11;

  // Jungsung: vowels, index 0-20
  // ㅏ ㅐ ㅑ ㅒ ㅓ ㅔ ㅕ ㅖ ㅗ ㅘ ㅙ ㅚ ㅛ ㅜ ㅝ ㅞ ㅟ ㅠ ㅡ ㅢ ㅣ
  private static final char[] JUNGSUNG = {
    'ㅏ','ㅐ','ㅑ','ㅒ','ㅓ','ㅔ','ㅕ','ㅖ','ㅗ','ㅘ','ㅙ','ㅚ','ㅛ','ㅜ','ㅝ','ㅞ','ㅟ','ㅠ','ㅡ','ㅢ','ㅣ'
  };

  // Jongsung: final consonants, slot 0 = none, slots 1-27 = valid final consonants
  // (none) ㄱ ㄲ ㄳ ㄴ ㄵ ㄶ ㄷ ㄹ ㄺ ㄻ ㄼ ㄽ ㄾ ㄿ ㅀ ㅁ ㅂ ㅄ ㅅ ㅆ ㅇ ㅈ ㅊ ㅋ ㅌ ㅍ ㅎ
  private static final char[] JONGSUNG = {
    0,'ㄱ','ㄲ','ㄳ','ㄴ','ㄵ','ㄶ','ㄷ','ㄹ','ㄺ','ㄻ','ㄼ','ㄽ','ㄾ','ㄿ','ㅀ',
    'ㅁ','ㅂ','ㅄ','ㅅ','ㅆ','ㅇ','ㅈ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'
  };

  // Current syllable being composed. -1 = absent.
  private int _cho  = -1;
  private int _jung = -1;
  private int _jong = -1;

  // ─── Index lookups ────────────────────────────────────────────────────────

  private static int indexOf(char[] arr, char c) {
    for (int i = 0; i < arr.length; i++)
      if (arr[i] == c) return i;
    return -1;
  }

  private static int chosungIdx(char c)  { return indexOf(CHOSUNG, c); }
  private static int jungsungIdx(char c) { return indexOf(JUNGSUNG, c); }
  // Returns 1-27 if c can be a final consonant, 0 otherwise
  private static int jongsungIdx(char c) { int i = indexOf(JONGSUNG, c); return i > 0 ? i : 0; }

  // ─── Syllable building ────────────────────────────────────────────────────

  private static char syllable(int cho, int jung, int jong) {
    return (char)(HANGUL_BASE + (cho * JUNGSUNG_COUNT + jung) * JONGSUNG_COUNT + jong);
  }

  // ─── Compound vowel table ─────────────────────────────────────────────────

  // Returns compound jungsung index, or -1 if no compound exists.
  private static int compoundJung(int j1, int j2) {
    char a = JUNGSUNG[j1], b = JUNGSUNG[j2];
    if (a=='ㅗ'&&b=='ㅏ') return indexOf(JUNGSUNG,'ㅘ');
    if (a=='ㅗ'&&b=='ㅐ') return indexOf(JUNGSUNG,'ㅙ');
    if (a=='ㅗ'&&b=='ㅣ') return indexOf(JUNGSUNG,'ㅚ');
    if (a=='ㅜ'&&b=='ㅓ') return indexOf(JUNGSUNG,'ㅝ');
    if (a=='ㅜ'&&b=='ㅔ') return indexOf(JUNGSUNG,'ㅞ');
    if (a=='ㅜ'&&b=='ㅣ') return indexOf(JUNGSUNG,'ㅟ');
    if (a=='ㅡ'&&b=='ㅣ') return indexOf(JUNGSUNG,'ㅢ');
    return -1;
  }

  // Returns the first component of a compound vowel, or -1 if not compound.
  private static int decomposeJung(int j) {
    switch (j) {
      case  9: case 10: case 11: return indexOf(JUNGSUNG,'ㅗ'); // ㅘ ㅙ ㅚ → ㅗ
      case 14: case 15: case 16: return indexOf(JUNGSUNG,'ㅜ'); // ㅝ ㅞ ㅟ → ㅜ
      case 19:                   return indexOf(JUNGSUNG,'ㅡ'); // ㅢ → ㅡ
      default:                   return -1;
    }
  }

  // ─── Compound jongsung table ──────────────────────────────────────────────

  // Returns compound jongsung index (1-27), or 0 if no compound exists.
  private static int compoundJong(int j1, int j2) {
    char a = JONGSUNG[j1], b = JONGSUNG[j2];
    if (a=='ㄱ'&&b=='ㅅ') return indexOf(JONGSUNG,'ㄳ');
    if (a=='ㄴ'&&b=='ㅈ') return indexOf(JONGSUNG,'ㄵ');
    if (a=='ㄴ'&&b=='ㅎ') return indexOf(JONGSUNG,'ㄶ');
    if (a=='ㄹ'&&b=='ㄱ') return indexOf(JONGSUNG,'ㄺ');
    if (a=='ㄹ'&&b=='ㅁ') return indexOf(JONGSUNG,'ㄻ');
    if (a=='ㄹ'&&b=='ㅂ') return indexOf(JONGSUNG,'ㄼ');
    if (a=='ㄹ'&&b=='ㅅ') return indexOf(JONGSUNG,'ㄽ');
    if (a=='ㄹ'&&b=='ㅌ') return indexOf(JONGSUNG,'ㄾ');
    if (a=='ㄹ'&&b=='ㅍ') return indexOf(JONGSUNG,'ㄿ');
    if (a=='ㄹ'&&b=='ㅎ') return indexOf(JONGSUNG,'ㅀ');
    if (a=='ㅂ'&&b=='ㅅ') return indexOf(JONGSUNG,'ㅄ');
    return 0;
  }

  private static boolean isCompoundJong(int j) {
    return j==3||j==5||j==6||j==9||j==10||j==11||j==12||j==13||j==14||j==15||j==18;
  }

  // Split compound jongsung: [0] = first part (stays as jongsung),
  //                          [1] = second part as chosung index of next syllable.
  private static int[] splitJong(int j) {
    switch (j) {
      case  3: return new int[]{indexOf(JONGSUNG,'ㄱ'), chosungIdx('ㅅ')};
      case  5: return new int[]{indexOf(JONGSUNG,'ㄴ'), chosungIdx('ㅈ')};
      case  6: return new int[]{indexOf(JONGSUNG,'ㄴ'), chosungIdx('ㅎ')};
      case  9: return new int[]{indexOf(JONGSUNG,'ㄹ'), chosungIdx('ㄱ')};
      case 10: return new int[]{indexOf(JONGSUNG,'ㄹ'), chosungIdx('ㅁ')};
      case 11: return new int[]{indexOf(JONGSUNG,'ㄹ'), chosungIdx('ㅂ')};
      case 12: return new int[]{indexOf(JONGSUNG,'ㄹ'), chosungIdx('ㅅ')};
      case 13: return new int[]{indexOf(JONGSUNG,'ㄹ'), chosungIdx('ㅌ')};
      case 14: return new int[]{indexOf(JONGSUNG,'ㄹ'), chosungIdx('ㅍ')};
      case 15: return new int[]{indexOf(JONGSUNG,'ㄹ'), chosungIdx('ㅎ')};
      case 18: return new int[]{indexOf(JONGSUNG,'ㅂ'), chosungIdx('ㅅ')};
      default: return null;
    }
  }

  // ─── Internal state helpers ───────────────────────────────────────────────

  private boolean composing() { return _cho >= 0; }

  private String composingText() {
    if (_cho < 0) return "";
    if (_jung < 0) return String.valueOf(CHOSUNG[_cho]);
    return String.valueOf(syllable(_cho, _jung, _jong < 0 ? 0 : _jong));
  }

  private void updateComposing(InputConnection conn) {
    conn.setComposingText(composingText(), 1);
  }

  // Commit the current composing text (as committed text, not composing) and reset state.
  private void commitCurrent(InputConnection conn) {
    conn.commitText(composingText(), 1);
    _cho = _jung = _jong = -1;
  }

  // ─── Public interface ─────────────────────────────────────────────────────

  /** Returns true if c is a Hangul compatibility jamo (U+3131..U+318E). */
  public static boolean isJamo(char c) {
    return c >= 'ㄱ' && c <= 'ㆎ';
  }

  /**
   * Process a jamo character input.
   * Must only be called when isJamo(c) is true.
   */
  public void input(char c, InputConnection conn) {
    conn.beginBatchEdit();
    int vi = jungsungIdx(c);
    if (vi >= 0) { inputVowel(vi, conn); conn.endBatchEdit(); return; }
    int ci = chosungIdx(c);
    int ji = jongsungIdx(c);
    inputConsonant(ci, ji, c, conn);
    conn.endBatchEdit();
  }

  private void inputVowel(int vi, InputConnection conn) {
    if (!composing()) {
      // Empty: start ㅇ + vowel
      _cho = CHOSUNG_IEUNG; _jung = vi; _jong = -1;
      updateComposing(conn);
    } else if (_jung < 0) {
      // State 1 (pending consonant): add vowel to make syllable
      _jung = vi;
      updateComposing(conn);
    } else if (_jong < 0) {
      // State 2 (cho+jung): try compound vowel
      int cv = compoundJung(_jung, vi);
      if (cv >= 0) {
        _jung = cv;
        updateComposing(conn);
      } else {
        // No compound: commit current, start new ㅇ+vowel
        commitCurrent(conn);
        _cho = CHOSUNG_IEUNG; _jung = vi; _jong = -1;
        updateComposing(conn);
      }
    } else {
      // State 3 (cho+jung+jong): jongsung splits
      if (isCompoundJong(_jong)) {
        int[] parts = splitJong(_jong);
        _jong = parts[0];       // first part stays in current syllable
        commitCurrent(conn);    // commit first syllable
        _cho = parts[1];        // second part becomes chosung of next syllable
        _jung = vi; _jong = -1;
        updateComposing(conn);
      } else {
        // Simple jongsung: move it to next syllable as chosung
        int newCho = chosungIdx(JONGSUNG[_jong]);
        _jong = -1;
        commitCurrent(conn);    // commit syllable without jongsung
        _cho = (newCho >= 0) ? newCho : CHOSUNG_IEUNG;
        _jung = vi; _jong = -1;
        updateComposing(conn);
      }
    }
  }

  private void inputConsonant(int ci, int ji, char raw, InputConnection conn) {
    if (!composing()) {
      if (ci >= 0) {
        _cho = ci; _jung = _jong = -1;
        updateComposing(conn);
      } else {
        // Compound/tense consonant with no valid chosung form: output raw
        conn.commitText(String.valueOf(raw), 1);
      }
    } else if (_jung < 0) {
      // State 1: two consonants in a row — commit first, start new
      commitCurrent(conn);
      if (ci >= 0) { _cho = ci; _jung = _jong = -1; updateComposing(conn); }
      else conn.commitText(String.valueOf(raw), 1);
    } else if (_jong < 0) {
      // State 2 (cho+jung): try to add as jongsung
      if (ji > 0) {
        _jong = ji;
        updateComposing(conn);
      } else {
        // ㄸ, ㅃ, ㅉ cannot be jongsung: commit and start new
        commitCurrent(conn);
        if (ci >= 0) { _cho = ci; _jung = _jong = -1; updateComposing(conn); }
        else conn.commitText(String.valueOf(raw), 1);
      }
    } else {
      // State 3 (cho+jung+jong): try compound jongsung
      if (ji > 0) {
        int cj = compoundJong(_jong, ji);
        if (cj > 0) {
          _jong = cj;
          updateComposing(conn);
        } else {
          // No compound: commit current, start new syllable
          commitCurrent(conn);
          if (ci >= 0) { _cho = ci; _jung = _jong = -1; updateComposing(conn); }
          else conn.commitText(String.valueOf(raw), 1);
        }
      } else {
        // Tense consonant or other: commit current, start new
        commitCurrent(conn);
        if (ci >= 0) { _cho = ci; _jung = _jong = -1; updateComposing(conn); }
        else conn.commitText(String.valueOf(raw), 1);
      }
    }
  }

  /**
   * Handle backspace. Returns true if consumed (automaton decomposed one step),
   * false to fall through to default backspace.
   */
  public boolean backspace(InputConnection conn) {
    if (!composing()) return false;
    if (_jong >= 0) {
      // Decompose jongsung first
      if (isCompoundJong(_jong)) {
        _jong = splitJong(_jong)[0]; // keep only first part
      } else {
        _jong = -1;
      }
      updateComposing(conn);
    } else if (_jung >= 0) {
      // Decompose vowel
      int first = decomposeJung(_jung);
      _jung = (first >= 0) ? first : -1;
      updateComposing(conn);
    } else {
      // Only chosung: delete the composing consonant entirely
      conn.commitText("", 1);
      _cho = _jung = _jong = -1;
    }
    return true;
  }

  /**
   * Commit any pending composing syllable as final text.
   * Safe to call when not composing (no-op).
   */
  public void commit(InputConnection conn) {
    if (composing()) commitCurrent(conn);
  }

  /**
   * Reset state without committing. Use on focus change where the new field
   * has no composing context.
   */
  public void reset() {
    _cho = _jung = _jong = -1;
  }
}
