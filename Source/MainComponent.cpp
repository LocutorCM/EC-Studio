#include "MainComponent.h"
namespace { const juce::Colour background(0xff0b101a), panel(0xff111a29), panelLight(0xff17243a), blue(0xff3296ff), cyan(0xff55d6e8), muted(0xff8292aa), white(0xffedf4ff); }
MainComponent::MainComponent()
{
 setSize(1280,800); setAudioChannels(2,2);
 auto style=[](juce::TextButton& b, juce::Colour c){ b.setColour(juce::TextButton::buttonColourId,c); b.setColour(juce::TextButton::buttonOnColourId,c.brighter(0.15f)); b.setColour(juce::TextButton::textColourOffId,white); b.setColour(juce::TextButton::textColourOnId,white); };
 style(recordButton,juce::Colour(0xffb8324b)); style(playButton,blue); style(stopButton,panelLight); style(exportButton,juce::Colour(0xff1d6e83));
 recordButton.onClick=[this]{startRecording();}; playButton.onClick=[this]{startPlayback();}; stopButton.onClick=[this]{stopTransport();}; exportButton.onClick=[this]{exportWav();};
 for(auto* c:{static_cast<juce::Component*>(&recordButton),&playButton,&stopButton,&exportButton}) addAndMakeVisible(c);
 projectLabel.setText("EC STUDIO  /  UNTITLED SESSION",juce::dontSendNotification); projectLabel.setFont(juce::Font(17.0f,juce::Font::bold)); projectLabel.setColour(juce::Label::textColourId,white); addAndMakeVisible(projectLabel);
 statusLabel.setText("READY  •  AUDIO ENGINE INITIALIZING",juce::dontSendNotification); statusLabel.setFont(juce::Font(12.0f)); statusLabel.setColour(juce::Label::textColourId,muted); addAndMakeVisible(statusLabel);
 timeLabel.setText("00:00.000",juce::dontSendNotification); timeLabel.setFont(juce::Font(juce::Font::getDefaultMonospacedFontName(),20.0f,juce::Font::bold)); timeLabel.setColour(juce::Label::textColourId,cyan); timeLabel.setJustificationType(juce::Justification::centred); addAndMakeVisible(timeLabel);
 startTimerHz(15);
}
MainComponent::~MainComponent(){ shutdownAudio(); }
void MainComponent::prepareToPlay(int,double rate)
{
 currentSampleRate=rate; maxRecordSamples=static_cast<int>(rate*600.0);
 const juce::ScopedLock lock(exportLock); recordedAudio.setSize(2,maxRecordSamples); recordedAudio.clear(); recordedSamples.store(0); playhead.store(0);
 statusLabel.setText("AUDIO ENGINE READY  •  "+juce::String(rate/1000.0,1)+" kHz",juce::dontSendNotification);
}
void MainComponent::getNextAudioBlock(const juce::AudioSourceChannelInfo& info)
{
 auto* out=info.buffer; if(out==nullptr)return;
 const int inputs=juce::jmin(getTotalNumInputChannels(),2), outputs=juce::jmin(getTotalNumOutputChannels(),2);
 if(recording.load() && inputs>0)
 {
  const int start=recordedSamples.load(), amount=juce::jmin(info.numSamples,maxRecordSamples-start);
  if(amount>0){ for(int ch=0;ch<2;++ch) recordedAudio.copyFrom(ch,start,*out,juce::jmin(ch,inputs-1),info.startSample,amount); recordedSamples.store(start+amount); }
  else recording.store(false);
 }
 out->clear(info.startSample,info.numSamples);
 if(playing.load())
 {
  const int pos=playhead.load(), amount=juce::jmin(info.numSamples,juce::jmax(0,recordedSamples.load()-pos));
  if(amount>0){ for(int ch=0;ch<outputs;++ch) out->copyFrom(inputs+ch,info.startSample,recordedAudio,ch,pos,amount); playhead.store(pos+amount); } else playing.store(false);
 }
}
void MainComponent::releaseResources(){}
void MainComponent::startRecording(){ playing.store(false); recordedSamples.store(0); playhead.store(0); recording.store(true); statusLabel.setText("RECORDING  •  CAPTURING INPUT",juce::dontSendNotification); }
void MainComponent::startPlayback(){ if(recordedSamples.load()<=0){statusLabel.setText("NO AUDIO YET  •  RECORD A TAKE FIRST",juce::dontSendNotification);return;} if(playhead.load()>=recordedSamples.load())playhead.store(0); recording.store(false); playing.store(true); statusLabel.setText("PLAYING  •  AUDIO TAKE",juce::dontSendNotification); }
void MainComponent::stopTransport(){ recording.store(false); playing.store(false); statusLabel.setText("STOPPED  •  "+juce::String(recordedSamples.load()/currentSampleRate,2)+" s RECORDED",juce::dontSendNotification); }
void MainComponent::exportWav()
{
 stopTransport(); const int length=recordedSamples.load(); if(length<=0){statusLabel.setText("NOTHING TO EXPORT  •  RECORD A TAKE FIRST",juce::dontSendNotification);return;}
 auto chooser=std::make_shared<juce::FileChooser>("Export audio as WAV",juce::File::getSpecialLocation(juce::File::userMusicDirectory).getChildFile("EC Studio Take.wav"),"*.wav");
 chooser->launchAsync(juce::FileBrowserComponent::saveMode|juce::FileBrowserComponent::canSelectFiles,[this,chooser,length](const juce::FileChooser& fc)
 {
  const auto file=fc.getResult(); if(file==juce::File{})return;
  auto stream=std::unique_ptr<juce::FileOutputStream>(file.createOutputStream());
  if(stream==nullptr||!stream->openedOk()){statusLabel.setText("EXPORT FAILED  •  COULD NOT CREATE FILE",juce::dontSendNotification);return;}
  juce::WavAudioFormat wav; auto options=juce::AudioFormatWriterOptions().withSampleRate(currentSampleRate).withNumChannels(2).withBitsPerSample(24);
  auto writer=wav.createWriterFor(std::move(stream),options);
  if(writer==nullptr){statusLabel.setText("EXPORT FAILED  •  WAV WRITER ERROR",juce::dontSendNotification);return;}
  const juce::ScopedLock lock(exportLock); const bool ok=writer->writeFromAudioSampleBuffer(recordedAudio,0,length); writer.reset();
  statusLabel.setText(ok?"EXPORTED  •  "+file.getFileName():"EXPORT FAILED",juce::dontSendNotification);
 });
}
void MainComponent::timerCallback()
{
 const double t=(playing.load()?playhead.load():recordedSamples.load())/currentSampleRate;
 timeLabel.setText(juce::String::formatted("%02d:%06.3f",static_cast<int>(t)/60,std::fmod(t,60.0)),juce::dontSendNotification); repaint();
}
void MainComponent::drawWaveform(juce::Graphics& g,juce::Rectangle<int> area)
{
 g.setColour(juce::Colour(0xff0a1422)); g.fillRoundedRectangle(area.toFloat(),8.0f); g.setColour(juce::Colour(0xff24364f));
 for(int i=0;i<=8;++i){float x=area.getX()+area.getWidth()*(i/8.0f);g.drawVerticalLine(static_cast<int>(x),static_cast<float>(area.getY()),static_cast<float>(area.getBottom()));}
 for(int i=1;i<=3;++i){float y=area.getY()+area.getHeight()*(i/4.0f);g.drawHorizontalLine(static_cast<int>(y),static_cast<float>(area.getX()),static_cast<float>(area.getRight()));}
 const int count=recordedSamples.load();
 if(count>0){const int cols=juce::jmax(1,area.getWidth()-12),step=juce::jmax(1,count/cols);const float mid=area.getCentreY(),amp=area.getHeight()*0.42f;g.setColour(cyan);juce::Path p;
  for(int x=0;x<cols;++x){int start=x*step;if(start>=count)break;float peak=0;int end=juce::jmin(count,start+step);for(int n=start;n<end;++n)peak=juce::jmax(peak,std::abs(recordedAudio.getSample(0,n)));float px=static_cast<float>(area.getX()+6+x),h=juce::jmax(1.0f,peak*amp);p.startNewSubPath(px,mid-h);p.lineTo(px,mid+h);}g.strokePath(p,juce::PathStrokeType(1.2f));}
 if(playing.load()||recording.load()){int pos=recording.load()?recordedSamples.load():playhead.load();float x=area.getX()+6.0f+area.getWidth()*(pos/static_cast<float>(juce::jmax(1,maxRecordSamples)));g.setColour(recording.load()?juce::Colour(0xffff526d):blue);g.drawVerticalLine(static_cast<int>(x),static_cast<float>(area.getY()),static_cast<float>(area.getBottom()));}
}
void MainComponent::paint(juce::Graphics& g)
{
 g.fillAll(background);g.setColour(panel);g.fillRect(0,0,getWidth(),76);g.setColour(blue);g.fillRect(0,74,getWidth(),2);
 g.setColour(white);g.setFont(juce::Font(26.0f,juce::Font::bold));g.drawText("EC",28,20,36,32,juce::Justification::centredLeft);g.setColour(blue);g.drawText("STUDIO",65,20,115,32,juce::Justification::centredLeft);
 g.setColour(muted);g.setFont(11.0f);g.drawText("DIGITAL AUDIO WORKSTATION  •  PRE-ALPHA",28,51,260,16,juce::Justification::centredLeft);
 g.setColour(panel);g.fillRoundedRectangle(24.0f,100.0f,getWidth()-48.0f,132.0f,12.0f);g.setColour(muted);g.setFont(11.0f);g.drawText("TRANSPORT",44,115,100,18,juce::Justification::centredLeft);g.setColour(white);g.setFont(juce::Font(14.0f,juce::Font::bold));g.drawText("SESSION",44,177,100,18,juce::Justification::centredLeft);
 g.setColour(panel);g.fillRoundedRectangle(24.0f,252.0f,getWidth()-48.0f,getHeight()-278.0f,12.0f);g.setColour(muted);g.setFont(11.0f);g.drawText("ARRANGEMENT",44,270,150,18,juce::Justification::centredLeft);
 auto track=juce::Rectangle<int>(44,310,getWidth()-88,170);g.setColour(panelLight);g.fillRoundedRectangle(track.toFloat(),8.0f);g.setColour(juce::Colour(0xff293b55));g.drawRoundedRectangle(track.toFloat(),8.0f,1.0f);
 g.setColour(white);g.setFont(juce::Font(13.0f,juce::Font::bold));g.drawText("AUDIO 01",58,324,110,20,juce::Justification::centredLeft);g.setColour(muted);g.setFont(11.0f);g.drawText("INPUT 1–2  •  STEREO",58,347,130,18,juce::Justification::centredLeft);drawWaveform(g,track.withTrimmedLeft(190).withTrimmedTop(18).withTrimmedBottom(18));
 g.setColour(muted);g.setFont(11.0f);g.drawText("More tracks, MIDI, plugin hosting and detailed editing are planned for upcoming milestones.",44,getHeight()-48,getWidth()-88,20,juce::Justification::centredLeft);
}
void MainComponent::resized()
{
 projectLabel.setBounds(310,20,330,28);statusLabel.setBounds(310,48,430,18);timeLabel.setBounds(getWidth()/2-85,126,170,36);
 recordButton.setBounds(getWidth()-470,132,112,38);playButton.setBounds(getWidth()-348,132,100,38);stopButton.setBounds(getWidth()-238,132,88,38);exportButton.setBounds(getWidth()-138,132,104,38);
}
