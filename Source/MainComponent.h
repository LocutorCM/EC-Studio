#pragma once
#include <JuceHeader.h>
#include <atomic>
class MainComponent final : public juce::AudioAppComponent, private juce::Timer
{
public:
 MainComponent(); ~MainComponent() override;
 void prepareToPlay(int samplesPerBlockExpected, double sampleRate) override;
 void getNextAudioBlock(const juce::AudioSourceChannelInfo& bufferToFill) override;
 void releaseResources() override; void paint(juce::Graphics& g) override; void resized() override;
private:
 void timerCallback() override; void startRecording(); void stopTransport(); void startPlayback(); void exportWav();
 void drawWaveform(juce::Graphics& g, juce::Rectangle<int> area);
 juce::TextButton recordButton{"●  RECORD"}, playButton{"▶  PLAY"}, stopButton{"■  STOP"}, exportButton{"EXPORT WAV"};
 juce::Label statusLabel, projectLabel, timeLabel;
 juce::AudioBuffer<float> recordedAudio;
 std::atomic<int> recordedSamples{0}, playhead{0};
 std::atomic<bool> recording{false}, playing{false};
 double currentSampleRate{44100.0}; int maxRecordSamples{0};
 juce::CriticalSection exportLock;
 JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(MainComponent)
};
