#pragma once
#include <JuceHeader.h>
#include <atomic>
#include <vector>

class MainComponent final : public juce::AudioAppComponent, private juce::Timer
{
public:
    MainComponent();
    ~MainComponent() override;

    void prepareToPlay(int samplesPerBlockExpected, double sampleRate) override;
    void getNextAudioBlock(const juce::AudioSourceChannelInfo& bufferToFill) override;
    void releaseResources() override;
    void paint(juce::Graphics& g) override;
    void resized() override;

private:
    struct Track
    {
        juce::String name;
        juce::Colour colour;
        bool muted = false;
        bool solo = false;
        float gain = 0.85f;
        float pan = 0.0f;
    };

    void timerCallback() override;
    void startRecording();
    void stopTransport();
    void startPlayback();
    void exportWav();
    void addAudioTrack();
    void toggleMixer();
    void drawWaveform(juce::Graphics& g, juce::Rectangle<int> area);
    void drawTrackLane(juce::Graphics& g, juce::Rectangle<int> area, int index);

    juce::TextButton recordButton{"●"};
    juce::TextButton playButton{"▶"};
    juce::TextButton stopButton{"■"};
    juce::TextButton exportButton{"Export"};
    juce::TextButton addTrackButton{"+  Audio"};
    juce::TextButton mixerButton{"Mixer"};
    juce::TextButton loopButton{"Loop"};
    juce::TextButton metronomeButton{"Click"};
    juce::ComboBox tempoBox;
    juce::Label projectLabel, statusLabel, timeLabel;

    std::vector<Track> tracks;
    bool mixerVisible = true;
    int tempo = 120;
    juce::AudioBuffer<float> recordedAudio;
    std::atomic<int> recordedSamples{0}, playhead{0};
    std::atomic<bool> recording{false}, playing{false};
    double currentSampleRate{44100.0};
    int maxRecordSamples{0};
    juce::CriticalSection exportLock;

    JUCE_DECLARE_NON_COPYABLE_WITH_LEAK_DETECTOR(MainComponent)
};
